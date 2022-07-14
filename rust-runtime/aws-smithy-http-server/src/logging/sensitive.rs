/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::fmt::{Debug, Display, Error, Formatter};

use super::REDACTED;

/// A wrapper used to modify the [`Display`] and [`Debug`] implementation of the inner structure
/// based on the feature flag `unredacted-logging`. When the `unredacted-logging` feature is enabled, the
/// implementations will defer to those on `T`, when disabled they will defer to [`REDACTED`].
///
/// Note that there are [`Display`] and [`Debug`] implementations for `&T` where `T: Display`
/// and `T: Debug` respectively - wrapping references is allowed for the cases when consuming the
/// inner struct is not desired.
///
/// # Example
///
/// ```
/// # use aws_smithy_http_server::logging::Sensitive;
/// # let address = "";
/// tracing::debug!(
///     name = %Sensitive("Alice"),
///     friends = ?Sensitive(["Bob"]),
///     address = ?Sensitive(&address)
/// );
/// ```
pub struct Sensitive<T>(pub T);

impl<T> Debug for Sensitive<T>
where
    T: Debug,
{
    fn fmt(&self, f: &mut Formatter<'_>) -> Result<(), Error> {
        if cfg!(feature = "unredacted-logging") {
            self.0.fmt(f)
        } else {
            Debug::fmt(&REDACTED, f)
        }
    }
}

impl<T> Display for Sensitive<T>
where
    T: Display,
{
    fn fmt(&self, f: &mut Formatter<'_>) -> Result<(), Error> {
        if cfg!(feature = "unredacted-logging") {
            self.0.fmt(f)
        } else {
            Display::fmt(&REDACTED, f)
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::logging::REDACTED;

    use super::Sensitive;

    #[test]
    fn debug() {
        let inner = "hello world";
        let sensitive = Sensitive(inner);
        let actual = format!("{:?}", sensitive);
        let expected = if cfg!(feature = "unredacted-logging") {
            format!("{:?}", inner)
        } else {
            format!("{:?}", REDACTED)
        };
        assert_eq!(actual, expected)
    }

    #[test]
    fn display() {
        let inner = "hello world";
        let sensitive = Sensitive(inner);
        let actual = format!("{}", sensitive);
        let expected = if cfg!(feature = "unredacted-logging") {
            format!("{}", inner)
        } else {
            format!("{}", REDACTED)
        };
        assert_eq!(actual, expected)
    }
}