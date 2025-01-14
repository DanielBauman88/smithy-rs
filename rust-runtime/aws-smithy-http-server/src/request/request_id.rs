/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! # Request IDs
//!
//! `aws-smithy-http-server` provides the [`ServerRequestId`].
//!
//! ## `ServerRequestId`
//!
//! A [`ServerRequestId`] is an opaque random identifier generated by the server every time it receives a request.
//! It uniquely identifies the request within that service instance. It can be used to collate all logs, events and
//! data related to a single operation.
//!
//! The [`ServerRequestId`] can be returned to the caller, who can in turn share the [`ServerRequestId`] to help the service owner in troubleshooting issues related to their usage of the service.
//!
//! The [`ServerRequestId`] is not meant to be propagated to downstream dependencies of the service. You should rely on a distributed tracing implementation for correlation purposes (e.g. OpenTelemetry).
//!
//! ## Examples
//!
//! Your handler can now optionally take as input a [`ServerRequestId`].
//!
//! ```rust,ignore
//! pub async fn handler(
//!     _input: Input,
//!     server_request_id: ServerRequestId,
//! ) -> Output {
//!     /* Use server_request_id */
//!     todo!()
//! }
//!
//! let app = Service::builder_without_plugins()
//!     .operation(handler)
//!     .build().unwrap();
//!
//! let app = app.layer(&ServerRequestIdProviderLayer::new()); /* Generate a server request ID */
//!
//! let bind: std::net::SocketAddr = format!("{}:{}", args.address, args.port)
//!     .parse()
//!     .expect("unable to parse the server bind address and port");
//! let server = hyper::Server::bind(&bind).serve(app.into_make_service());
//! ```

use std::{
    fmt::Display,
    task::{Context, Poll},
};

use http::request::Parts;
use thiserror::Error;
use tower::{Layer, Service};
use uuid::Uuid;

use crate::{body::BoxBody, response::IntoResponse};

use super::{internal_server_error, FromParts};

/// Opaque type for Server Request IDs.
///
/// If it is missing, the request will be rejected with a `500 Internal Server Error` response.
#[derive(Clone, Debug)]
pub struct ServerRequestId {
    id: Uuid,
}

/// The server request ID has not been added to the [`Request`](http::Request) or has been previously removed.
#[non_exhaustive]
#[derive(Debug, Error)]
#[error("the `ServerRequestId` is not present in the `http::Request`")]
pub struct MissingServerRequestId;

impl ServerRequestId {
    pub fn new() -> Self {
        Self { id: Uuid::new_v4() }
    }
}

impl Display for ServerRequestId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.id.fmt(f)
    }
}

impl<P> FromParts<P> for ServerRequestId {
    type Rejection = MissingServerRequestId;

    fn from_parts(parts: &mut Parts) -> Result<Self, Self::Rejection> {
        parts.extensions.remove().ok_or(MissingServerRequestId)
    }
}

impl Default for ServerRequestId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone)]
pub struct ServerRequestIdProvider<S> {
    inner: S,
}

/// A layer that provides services with a unique request ID instance
#[derive(Debug)]
#[non_exhaustive]
pub struct ServerRequestIdProviderLayer;

impl ServerRequestIdProviderLayer {
    /// Generate a new unique request ID
    pub fn new() -> Self {
        Self {}
    }
}

impl Default for ServerRequestIdProviderLayer {
    fn default() -> Self {
        Self::new()
    }
}

impl<S> Layer<S> for ServerRequestIdProviderLayer {
    type Service = ServerRequestIdProvider<S>;

    fn layer(&self, inner: S) -> Self::Service {
        ServerRequestIdProvider { inner }
    }
}

impl<Body, S> Service<http::Request<Body>> for ServerRequestIdProvider<S>
where
    S: Service<http::Request<Body>>,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = S::Future;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut req: http::Request<Body>) -> Self::Future {
        req.extensions_mut().insert(ServerRequestId::new());
        self.inner.call(req)
    }
}

impl<Protocol> IntoResponse<Protocol> for MissingServerRequestId {
    fn into_response(self) -> http::Response<BoxBody> {
        internal_server_error()
    }
}
