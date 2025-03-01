/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustReservedWords
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.ErrorsModule
import software.amazon.smithy.rust.codegen.core.smithy.InputsModule
import software.amazon.smithy.rust.codegen.core.smithy.OutputsModule
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocolGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocolTestGenerator

/**
 * ServerServiceGenerator
 *
 * Service generator is the main code generation entry point for Smithy services. Individual structures and unions are
 * generated in codegen visitor, but this class handles all protocol-specific code generation (i.e. operations).
 */
open class ServerServiceGenerator(
    private val rustCrate: RustCrate,
    private val protocolGenerator: ServerProtocolGenerator,
    private val protocolSupport: ProtocolSupport,
    val protocol: ServerProtocol,
    private val codegenContext: ServerCodegenContext,
) {
    private val index = TopDownIndex.of(codegenContext.model)
    protected val operations = index.getContainedOperations(codegenContext.serviceShape).sortedBy { it.id }
    private val serviceName = codegenContext.serviceShape.id.name.toString()

    fun documentation(writer: RustWriter) {
        val operations = index.getContainedOperations(codegenContext.serviceShape).toSortedSet(compareBy { it.id })
        val builderFieldNames =
            operations.associateWith { RustReservedWords.escapeIfNeeded(codegenContext.symbolProvider.toSymbol(it).name.toSnakeCase()) }
                .toSortedMap(
                    compareBy { it.id },
                )
        val crateName = codegenContext.moduleUseName()
        val builderName = "${serviceName}Builder"
        val hasErrors = operations.any { it.errors.isNotEmpty() }
        val handlers: Writable = operations
            .map { operation ->
                DocHandlerGenerator(codegenContext, operation, builderFieldNames[operation]!!, "//!")::render
            }
            .join("//!\n")

        writer.rustTemplate(
            """
            //! A fast and customizable Rust implementation of the $serviceName Smithy service.
            //!
            //! ## Using $serviceName
            //!
            //! The primary entrypoint is [`$serviceName`]: it satisfies the [`Service<http::Request, Response = http::Response>`](#{Tower}::Service)
            //! trait and therefore can be handed to a [`hyper` server](https://github.com/hyperium/hyper) via [`$serviceName::into_make_service`] or used in Lambda via [`LambdaHandler`](#{SmithyHttpServer}::routing::LambdaHandler).
            //! The [`crate::${InputsModule.name}`], ${if (!hasErrors) "and " else ""}[`crate::${OutputsModule.name}`], ${if (hasErrors) "and [`crate::${ErrorsModule.name}`]" else "" }
            //! modules provide the types used in each operation.
            //!
            //! ###### Running on Hyper
            //!
            //! ```rust,no_run
            //! ## use std::net::SocketAddr;
            //! ## async fn dummy() {
            //! use $crateName::$serviceName;
            //!
            //! ## let app = $serviceName::builder_without_plugins().build_unchecked();
            //! let server = app.into_make_service();
            //! let bind: SocketAddr = "127.0.0.1:6969".parse()
            //!     .expect("unable to parse the server bind address and port");
            //! #{Hyper}::Server::bind(&bind).serve(server).await.unwrap();
            //! ## }
            //! ```
            //!
            //! ###### Running on Lambda
            //!
            //! This requires the `aws-lambda` feature flag to be passed to the [`#{SmithyHttpServer}`] crate.
            //!
            //! ```rust,ignore
            //! use #{SmithyHttpServer}::routing::LambdaHandler;
            //! use $crateName::$serviceName;
            //!
            //! ## async fn dummy() {
            //! ## let app = $serviceName::builder_without_plugins().build_unchecked();
            //! let handler = LambdaHandler::new(app);
            //! lambda_http::run(handler).await.unwrap();
            //! ## }
            //! ```
            //!
            //! ## Building the $serviceName
            //!
            //! To construct [`$serviceName`] we use [`$builderName`] returned by [`$serviceName::builder_without_plugins`]
            //! or [`$serviceName::builder_with_plugins`].
            //!
            //! #### Plugins
            //!
            //! The [`$serviceName::builder_with_plugins`] method, returning [`$builderName`],
            //! accepts a [`Plugin`](aws_smithy_http_server::plugin::Plugin).
            //! Plugins allow you to build middleware which is aware of the operation it is being applied to.
            //!
            //! ```rust
            //! ## use #{SmithyHttpServer}::plugin::IdentityPlugin as LoggingPlugin;
            //! ## use #{SmithyHttpServer}::plugin::IdentityPlugin as MetricsPlugin;
            //! ## use #{Hyper}::Body;
            //! use #{SmithyHttpServer}::plugin::PluginPipeline;
            //! use $crateName::{$serviceName, $builderName};
            //!
            //! let plugins = PluginPipeline::new()
            //!         .push(LoggingPlugin)
            //!         .push(MetricsPlugin);
            //! let builder: $builderName<Body, _> = $serviceName::builder_with_plugins(plugins);
            //! ```
            //!
            //! Check out [`#{SmithyHttpServer}::plugin`] to learn more about plugins.
            //!
            //! #### Handlers
            //!
            //! [`$builderName`] provides a setter method for each operation in your Smithy model. The setter methods expect an async function as input, matching the signature for the corresponding operation in your Smithy model.
            //! We call these async functions **handlers**. This is where your application business logic lives.
            //!
            //! Every handler must take an `Input`, and optional [`extractor arguments`](#{SmithyHttpServer}::request), while returning:
            //!
            //! * A `Result<Output, Error>` if your operation has modeled errors, or
            //! * An `Output` otherwise.
            //!
            //! ```rust
            //! ## struct Input;
            //! ## struct Output;
            //! ## struct Error;
            //! async fn infallible_handler(input: Input) -> Output { todo!() }
            //!
            //! async fn fallible_handler(input: Input) -> Result<Output, Error> { todo!() }
            //! ```
            //!
            //! Handlers can accept up to 8 extractors:
            //!
            //! ```rust
            //! ## struct Input;
            //! ## struct Output;
            //! ## struct Error;
            //! ## struct State;
            //! ## use std::net::SocketAddr;
            //! use #{SmithyHttpServer}::request::{extension::Extension, connect_info::ConnectInfo};
            //!
            //! async fn handler_with_no_extensions(input: Input) -> Output {
            //!     todo!()
            //! }
            //!
            //! async fn handler_with_one_extractor(input: Input, ext: Extension<State>) -> Output {
            //!     todo!()
            //! }
            //!
            //! async fn handler_with_two_extractors(
            //!     input: Input,
            //!     ext0: Extension<State>,
            //!     ext1: ConnectInfo<SocketAddr>,
            //! ) -> Output {
            //!     todo!()
            //! }
            //! ```
            //!
            //! See the [`operation module`](#{SmithyHttpServer}::operation) for information on precisely what constitutes a handler.
            //!
            //! #### Build
            //!
            //! You can convert [`$builderName`] into [`$serviceName`] using either [`$builderName::build`] or [`$builderName::build_unchecked`].
            //!
            //! [`$builderName::build`] requires you to provide a handler for every single operation in your Smithy model. It will return an error if that is not the case.
            //!
            //! [`$builderName::build_unchecked`], instead, does not require exhaustiveness. The server will automatically return 500 Internal Server Error to all requests for operations that do not have a registered handler.
            //! [`$builderName::build_unchecked`] is particularly useful if you are deploying your Smithy service as a collection of Lambda functions, where each Lambda is only responsible for a subset of the operations in the Smithy service (or even a single one!).
            //!
            //! ## Example
            //!
            //! ```rust
            //! ## use std::net::SocketAddr;
            //! use $crateName::$serviceName;
            //!
            //! ##[#{Tokio}::main]
            //! pub async fn main() {
            //!    let app = $serviceName::builder_without_plugins()
            ${builderFieldNames.values.joinToString("\n") { "//!        .$it($it)" }}
            //!        .build()
            //!        .expect("failed to build an instance of $serviceName");
            //!
            //!    let bind: SocketAddr = "127.0.0.1:6969".parse()
            //!        .expect("unable to parse the server bind address and port");
            //!    let server = #{Hyper}::Server::bind(&bind).serve(app.into_make_service());
            //!    ## let server = async { Ok::<_, ()>(()) };
            //!
            //!    // Run your service!
            //!    if let Err(err) = server.await {
            //!        eprintln!("server error: {:?}", err);
            //!    }
            //! }
            //!
            #{HandlerImports:W}
            //!
            #{Handlers:W}
            //!
            //! ```
            //!
            //! [`serve`]: https://docs.rs/hyper/0.14.16/hyper/server/struct.Builder.html##method.serve
            //! [`tower::make::MakeService`]: https://docs.rs/tower/latest/tower/make/trait.MakeService.html
            //! [HTTP binding traits]: https://smithy.io/2.0/spec/http-bindings.html
            //! [operations]: https://smithy.io/2.0/spec/service-types.html##operation
            //! [hyper server]: https://docs.rs/hyper/latest/hyper/server/index.html
            //! [Service]: https://docs.rs/tower-service/latest/tower_service/trait.Service.html
            """,
            "HandlerImports" to handlerImports(crateName, operations, commentToken = "//!"),
            "Handlers" to handlers,
            "ExampleHandler" to operations.take(1).map { operation -> DocHandlerGenerator(codegenContext, operation, builderFieldNames[operation]!!, "//!").docSignature() },
            "SmithyHttpServer" to ServerCargoDependency.smithyHttpServer(codegenContext.runtimeConfig).toType(),
            "Hyper" to ServerCargoDependency.HyperDev.toType(),
            "Tokio" to ServerCargoDependency.TokioDev.toType(),
            "Tower" to ServerCargoDependency.Tower.toType(),
        )
    }

    /**
     * Render Service Specific code. Code will end up in different files via [useShapeWriter]. See `SymbolVisitor.kt`
     * which assigns a symbol location to each shape.
     */
    fun render() {
        rustCrate.lib {
            documentation(this)

            rust("pub use crate::service::{$serviceName, ${serviceName}Builder, MissingOperationsError};")
        }

        rustCrate.withModule(RustModule.operation(Visibility.PRIVATE)) {
            ServerProtocolTestGenerator(codegenContext, protocolSupport, protocolGenerator).render(this)
        }

        for (operation in operations) {
            if (operation.errors.isNotEmpty()) {
                rustCrate.withModule(RustModule.Error) {
                    renderCombinedErrors(this, operation)
                }
            }
        }

        rustCrate.withModule(
            RustModule.public("operation_shape"),
        ) {
            ServerOperationShapeGenerator(operations, codegenContext).render(this)
        }

        rustCrate.withModule(
            RustModule.private("service"),
        ) {
            ServerServiceGeneratorV2(
                codegenContext,
                protocol,
            ).render(this)
        }

        renderExtras(operations)

        rustCrate.withModule(
            RustModule.public(
                "server",
                """
                Contains the types that are re-exported from the `aws-smithy-http-server` create.
                """,
            ),
        ) {
            renderServerReExports(this)
        }
    }

    // Render any extra section needed by subclasses of `ServerServiceGenerator`.
    open fun renderExtras(operations: List<OperationShape>) { }

    // Render combined errors.
    open fun renderCombinedErrors(writer: RustWriter, operation: OperationShape) {
        /* Subclasses can override */
    }

    // Render `server` crate, re-exporting types.
    private fun renderServerReExports(writer: RustWriter) {
        ServerRuntimeTypesReExportsGenerator(codegenContext).render(writer)
    }
}
