package app.offlinefin.core

import java.security.cert.CertificateException

/**
 * Thrown by the TLS trust check when a server presents a certificate that the system does not
 * trust and whose public key is not the one the user pinned. `AppError.from` maps it to
 * `TLS_PIN_CHANGED`. The message is fixed text; it never includes a host or certificate detail.
 */
class PinMismatchException : CertificateException("Server certificate does not match the pinned key.")
