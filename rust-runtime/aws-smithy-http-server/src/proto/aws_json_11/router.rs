/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::body::{empty, BoxBody};
use crate::extension::RuntimeErrorExtension;
use crate::proto::aws_json::router::Error;
use crate::response::IntoResponse;
use crate::routing::{method_disallowed, UNKNOWN_OPERATION_EXCEPTION};

use super::AwsJson1_1;

pub use crate::proto::aws_json::router::*;

impl IntoResponse<AwsJson1_1> for Error {
    fn into_response(self) -> http::Response<BoxBody> {
        match self {
            Error::MethodNotAllowed => method_disallowed(),
            _ => http::Response::builder()
                .status(http::StatusCode::NOT_FOUND)
                .header(http::header::CONTENT_TYPE, "application/x-amz-json-1.1")
                .extension(RuntimeErrorExtension::new(
                    UNKNOWN_OPERATION_EXCEPTION.to_string(),
                ))
                .body(empty())
                .expect("invalid HTTP response for AWS JSON routing error; please file a bug report under https://github.com/awslabs/smithy-rs/issues"),
        }
    }
}
