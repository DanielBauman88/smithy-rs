/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.parse

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.ByteShape
import software.amazon.smithy.model.shapes.IntegerShape
import software.amazon.smithy.model.shapes.LongShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShortShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EventHeaderTrait
import software.amazon.smithy.model.traits.EventPayloadTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.conditionalBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.eventStreamErrorSymbol
import software.amazon.smithy.rust.codegen.core.smithy.generators.renderUnknownVariant
import software.amazon.smithy.rust.codegen.core.smithy.generators.setterName
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticEventStreamUnionTrait
import software.amazon.smithy.rust.codegen.core.smithy.transformers.eventStreamErrors
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.toPascalCase

class EventStreamUnmarshallerGenerator(
    private val protocol: Protocol,
    codegenContext: CodegenContext,
    private val operationShape: OperationShape,
    private val unionShape: UnionShape,
    /** Function that maps a StructureShape into its builder symbol */
    private val builderSymbol: (StructureShape) -> Symbol,
) {
    private val model = codegenContext.model
    private val symbolProvider = codegenContext.symbolProvider
    private val codegenTarget = codegenContext.target
    private val runtimeConfig = codegenContext.runtimeConfig
    private val unionSymbol = symbolProvider.toSymbol(unionShape)
    private val errorSymbol = if (codegenTarget == CodegenTarget.SERVER && unionShape.eventStreamErrors().isEmpty()) {
        RuntimeType.smithyHttp(runtimeConfig).resolve("event_stream::MessageStreamError").toSymbol()
    } else {
        unionShape.eventStreamErrorSymbol(symbolProvider).toSymbol()
    }
    private val smithyEventStream = RuntimeType.smithyEventStream(runtimeConfig)
    private val eventStreamSerdeModule = RustModule.private("event_stream_serde")
    private val codegenScope = arrayOf(
        "Blob" to RuntimeType.blob(runtimeConfig),
        "expect_fns" to smithyEventStream.resolve("smithy"),
        "MarshallMessage" to smithyEventStream.resolve("frame::MarshallMessage"),
        "Message" to smithyEventStream.resolve("frame::Message"),
        "Header" to smithyEventStream.resolve("frame::Header"),
        "HeaderValue" to smithyEventStream.resolve("frame::HeaderValue"),
        "Error" to smithyEventStream.resolve("error::Error"),
        "OpError" to errorSymbol,
        "SmithyError" to RuntimeType.smithyTypes(runtimeConfig).resolve("Error"),
        "tracing" to RuntimeType.Tracing,
        "UnmarshalledMessage" to smithyEventStream.resolve("frame::UnmarshalledMessage"),
        "UnmarshallMessage" to smithyEventStream.resolve("frame::UnmarshallMessage"),
    )

    fun render(): RuntimeType {
        val unmarshallerType = unionShape.eventStreamUnmarshallerType()
        return RuntimeType.forInlineFun("${unmarshallerType.name}::new", eventStreamSerdeModule) {
            renderUnmarshaller(unmarshallerType, unionSymbol)
        }
    }

    private fun RustWriter.renderUnmarshaller(unmarshallerType: RuntimeType, unionSymbol: Symbol) {
        rust(
            """
            ##[non_exhaustive]
            ##[derive(Debug)]
            pub struct ${unmarshallerType.name};

            impl ${unmarshallerType.name} {
                pub fn new() -> Self {
                    ${unmarshallerType.name}
                }
            }
            """,
        )

        rustBlockTemplate(
            "impl #{UnmarshallMessage} for ${unmarshallerType.name}",
            *codegenScope,
        ) {
            rust("type Output = #T;", unionSymbol)
            rust("type Error = #T;", errorSymbol)

            rustBlockTemplate(
                """
                fn unmarshall(
                    &self,
                    message: &#{Message}
                ) -> std::result::Result<#{UnmarshalledMessage}<Self::Output, Self::Error>, #{Error}>
                """,
                *codegenScope,
            ) {
                rustTemplate("let response_headers = #{expect_fns}::parse_response_headers(message)?;", *codegenScope)
                rustBlock("match response_headers.message_type.as_str()") {
                    rustBlock("\"event\" => ") {
                        renderUnmarshallEvent()
                    }
                    rustBlock("\"exception\" => ") {
                        renderUnmarshallError()
                    }
                    rustBlock("value => ") {
                        rustTemplate(
                            "return Err(#{Error}::unmarshalling(format!(\"unrecognized :message-type: {}\", value)));",
                            *codegenScope,
                        )
                    }
                }
            }
        }
    }

    private fun expectedContentType(payloadTarget: Shape): String? = when (payloadTarget) {
        is BlobShape -> "application/octet-stream"
        is StringShape -> "text/plain"
        else -> null
    }

    private fun RustWriter.renderUnmarshallEvent() {
        rustBlock("match response_headers.smithy_type.as_str()") {
            for (member in unionShape.members()) {
                val target = model.expectShape(member.target, StructureShape::class.java)
                rustBlock("${member.memberName.dq()} => ") {
                    renderUnmarshallUnionMember(member, target)
                }
            }
            rustBlock("_unknown_variant => ") {
                when (codegenTarget.renderUnknownVariant()) {
                    true -> rustTemplate(
                        "Ok(#{UnmarshalledMessage}::Event(#{Output}::${UnionGenerator.UnknownVariantName}))",
                        "Output" to unionSymbol,
                        *codegenScope,
                    )
                    false -> rustTemplate(
                        "return Err(#{Error}::unmarshalling(format!(\"unrecognized :event-type: {}\", _unknown_variant)));",
                        *codegenScope,
                    )
                }
            }
        }
    }

    private fun RustWriter.renderUnmarshallUnionMember(unionMember: MemberShape, unionStruct: StructureShape) {
        val unionMemberName = symbolProvider.toMemberName(unionMember)
        val empty = unionStruct.members().isEmpty()
        val payloadOnly =
            unionStruct.members().none { it.hasTrait<EventPayloadTrait>() || it.hasTrait<EventHeaderTrait>() }
        when {
            // Don't attempt to parse the payload for an empty struct. The payload can be empty, or if the model was
            // updated since the code was generated, it can have content that would not be understood.
            empty -> {
                rustTemplate(
                    "Ok(#{UnmarshalledMessage}::Event(#{Output}::$unionMemberName(#{UnionStruct}::builder().build())))",
                    "Output" to unionSymbol,
                    "UnionStruct" to symbolProvider.toSymbol(unionStruct),
                    *codegenScope,
                )
            }
            payloadOnly -> {
                withBlock("let parsed = ", ";") {
                    renderParseProtocolPayload(unionMember)
                }
                rustTemplate(
                    "Ok(#{UnmarshalledMessage}::Event(#{Output}::$unionMemberName(parsed)))",
                    "Output" to unionSymbol,
                    *codegenScope,
                )
            }
            else -> {
                rust("let mut builder = #T::default();", builderSymbol(unionStruct))
                val payloadMember = unionStruct.members().firstOrNull { it.hasTrait<EventPayloadTrait>() }
                if (payloadMember != null) {
                    renderUnmarshallEventPayload(payloadMember)
                }
                val headerMembers = unionStruct.members().filter { it.hasTrait<EventHeaderTrait>() }
                if (headerMembers.isNotEmpty()) {
                    rustBlock("for header in message.headers()") {
                        rustBlock("match header.name().as_str()") {
                            for (member in headerMembers) {
                                rustBlock("${member.memberName.dq()} => ") {
                                    renderUnmarshallEventHeader(member)
                                }
                            }
                            rust("// Event stream protocol headers start with ':'")
                            rustBlock("name => if !name.starts_with(':')") {
                                rustTemplate(
                                    "#{tracing}::trace!(\"Unrecognized event stream message header: {}\", name);",
                                    *codegenScope,
                                )
                            }
                        }
                    }
                }
                rustTemplate(
                    "Ok(#{UnmarshalledMessage}::Event(#{Output}::$unionMemberName(builder.build())))",
                    "Output" to unionSymbol,
                    *codegenScope,
                )
            }
        }
    }

    private fun RustWriter.renderUnmarshallEventHeader(member: MemberShape) {
        withBlock("builder = builder.${member.setterName()}(", ");") {
            conditionalBlock("Some(", ")", member.isOptional) {
                when (val target = model.expectShape(member.target)) {
                    is BooleanShape -> rustTemplate("#{expect_fns}::expect_bool(header)?", *codegenScope)
                    is ByteShape -> rustTemplate("#{expect_fns}::expect_byte(header)?", *codegenScope)
                    is ShortShape -> rustTemplate("#{expect_fns}::expect_int16(header)?", *codegenScope)
                    is IntegerShape -> rustTemplate("#{expect_fns}::expect_int32(header)?", *codegenScope)
                    is LongShape -> rustTemplate("#{expect_fns}::expect_int64(header)?", *codegenScope)
                    is BlobShape -> rustTemplate("#{expect_fns}::expect_byte_array(header)?", *codegenScope)
                    is StringShape -> rustTemplate("#{expect_fns}::expect_string(header)?", *codegenScope)
                    is TimestampShape -> rustTemplate("#{expect_fns}::expect_timestamp(header)?", *codegenScope)
                    else -> throw IllegalStateException("unsupported event stream header shape type: $target")
                }
            }
        }
    }

    private fun RustWriter.renderUnmarshallEventPayload(member: MemberShape) {
        // TODO(EventStream): [RPC] Don't blow up on an initial-message that's not part of the union (:event-type will be "initial-request" or "initial-response")
        // TODO(EventStream): [RPC] Incorporate initial-message into original output (:event-type will be "initial-request" or "initial-response")
        val target = model.expectShape(member.target)
        expectedContentType(target)?.also { contentType ->
            rustTemplate(
                """
                let content_type = response_headers.content_type().unwrap_or_default();
                if content_type != ${contentType.dq()} {
                    return Err(#{Error}::unmarshalling(format!(
                        "expected :content-type to be '$contentType', but was '{}'",
                        content_type
                    )))
                }
                """,
                *codegenScope,
            )
        }
        withBlock("builder = builder.${member.setterName()}(", ");") {
            conditionalBlock("Some(", ")", member.isOptional) {
                when (target) {
                    is BlobShape -> {
                        rustTemplate("#{Blob}::new(message.payload().as_ref())", *codegenScope)
                    }
                    is StringShape -> {
                        rustTemplate(
                            """
                            std::str::from_utf8(message.payload())
                                .map_err(|_| #{Error}::unmarshalling("message payload is not valid UTF-8"))?
                                .to_owned()
                            """,
                            *codegenScope,
                        )
                    }
                    is UnionShape, is StructureShape -> {
                        renderParseProtocolPayload(member)
                    }
                }
            }
        }
    }

    private fun RustWriter.renderParseProtocolPayload(member: MemberShape) {
        val memberName = symbolProvider.toMemberName(member)
        val parser = protocol.structuredDataParser(operationShape).payloadParser(member)
        rustTemplate(
            """
            #{parser}(&message.payload()[..])
                .map_err(|err| {
                    #{Error}::unmarshalling(format!("failed to unmarshall $memberName: {}", err))
                })?
            """,
            "parser" to parser,
            *codegenScope,
        )
    }

    private fun RustWriter.renderUnmarshallError() {
        when (codegenTarget) {
            CodegenTarget.CLIENT -> {
                rustTemplate(
                    """
                    let generic = match #{parse_generic_error}(message.payload()) {
                        Ok(generic) => generic,
                        Err(err) => return Ok(#{UnmarshalledMessage}::Error(#{OpError}::unhandled(err))),
                    };
                    """,
                    "parse_generic_error" to protocol.parseEventStreamGenericError(operationShape),
                    *codegenScope,
                )
            }
            CodegenTarget.SERVER -> {}
        }

        val syntheticUnion = unionShape.expectTrait<SyntheticEventStreamUnionTrait>()
        if (syntheticUnion.errorMembers.isNotEmpty()) {
            // clippy::single-match implied, using if when there's only one error
            val (header, matchOperator) = if (syntheticUnion.errorMembers.size > 1) {
                listOf("match response_headers.smithy_type.as_str() {", "=>")
            } else {
                listOf("if response_headers.smithy_type.as_str() == ", "")
            }
            rust(header)
            for (member in syntheticUnion.errorMembers) {
                rustBlock("${member.memberName.dq()} $matchOperator ") {
                    // TODO(EventStream): Errors on the operation can be disjoint with errors in the union,
                    //  so we need to generate a new top-level Error type for each event stream union.
                    when (codegenTarget) {
                        CodegenTarget.CLIENT -> {
                            val target = model.expectShape(member.target, StructureShape::class.java)
                            val parser = protocol.structuredDataParser(operationShape).errorParser(target)
                            if (parser != null) {
                                rust("let mut builder = #T::default();", builderSymbol(target))
                                rustTemplate(
                                    """
                                    builder = #{parser}(&message.payload()[..], builder)
                                        .map_err(|err| {
                                            #{Error}::unmarshalling(format!("failed to unmarshall ${member.memberName}: {}", err))
                                        })?;
                                    return Ok(#{UnmarshalledMessage}::Error(
                                        #{OpError}::new(
                                            #{OpError}Kind::${member.target.name}(builder.build()),
                                            generic,
                                        )
                                    ))
                                    """,
                                    "parser" to parser,
                                    *codegenScope,
                                )
                            }
                        }
                        CodegenTarget.SERVER -> {
                            val target = model.expectShape(member.target, StructureShape::class.java)
                            val parser = protocol.structuredDataParser(operationShape).errorParser(target)
                            val mut = if (parser != null) { " mut" } else { "" }
                            rust("let$mut builder = #T::default();", builderSymbol(target))
                            if (parser != null) {
                                rustTemplate(
                                    """
                                    builder = #{parser}(&message.payload()[..], builder)
                                        .map_err(|err| {
                                            #{Error}::unmarshalling(format!("failed to unmarshall ${member.memberName}: {}", err))
                                        })?;
                                    """,
                                    "parser" to parser,
                                    *codegenScope,
                                )
                            }
                            rustTemplate(
                                """
                                return Ok(#{UnmarshalledMessage}::Error(
                                    #{OpError}::${member.target.name}(
                                        builder.build()
                                    )
                                ))
                                """,
                                *codegenScope,
                            )
                        }
                    }
                }
            }
            if (syntheticUnion.errorMembers.size > 1) {
                // it's: match ... {
                rust("_ => {}")
                rust("}")
            }
        }
        when (codegenTarget) {
            CodegenTarget.CLIENT -> {
                rustTemplate("Ok(#{UnmarshalledMessage}::Error(#{OpError}::generic(generic)))", *codegenScope)
            }
            CodegenTarget.SERVER -> {
                rustTemplate(
                    """
                    return Err(aws_smithy_eventstream::error::Error::unmarshalling(
                    format!("unrecognized exception: {}", response_headers.smithy_type.as_str()),
                    ));
                    """,
                    *codegenScope,
                )
            }
        }
    }

    private fun UnionShape.eventStreamUnmarshallerType(): RuntimeType {
        val symbol = symbolProvider.toSymbol(this)
        return RuntimeType("crate::event_stream_serde::${symbol.name.toPascalCase()}Unmarshaller")
    }
}
