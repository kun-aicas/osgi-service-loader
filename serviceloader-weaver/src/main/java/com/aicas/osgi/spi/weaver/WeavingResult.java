/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import java.util.Objects;

/**
 * Result returned by {@link ServiceLoaderWeaver#weave(byte[])}.
 */
public final class WeavingResult
{
  /** The possible outcomes of one weaving attempt. */
  public enum Status
  {
    /** Woven class bytes were produced. */
    WOVEN,

    /** The class contains no ServiceLoader invocation that the weaver supports. */
    NO_SUPPORTED_INVOCATION,

    /** The class contains a ServiceLoader invocation that the weaver does not support. */
    UNSUPPORTED_INVOCATION,

    /** Shared text or unknown attributes prevent safe replacement at fixed indices. */
    UNSAFE_CONSTANT_POOL,

    /** Parsing or transformation failed; the original bytes are retained. */
    TRANSFORMATION_ERROR
  }

  private final Status status;
  private final byte[] bytes;
  private Exception exception = null;

  WeavingResult(Status status,
                byte[] bytes)
  {
    this.status = Objects.requireNonNull(status, "status");
    this.bytes = Objects.requireNonNull(bytes, "bytes");
  }

  static WeavingResult woven(byte[] wovenBytes)
  {
    return new WeavingResult(Status.WOVEN,
                             wovenBytes);
  }

  static WeavingResult noSupportedInvocation(byte[] originalBytes)
  {
    return new WeavingResult(Status.NO_SUPPORTED_INVOCATION,
                             originalBytes);
  }

  static WeavingResult unsupportedInvocation(byte[] originalBytes)
  {
    return new WeavingResult(Status.UNSUPPORTED_INVOCATION,
                             originalBytes);
  }

  static WeavingResult transformationError(byte[] originalBytes,
                                         RuntimeException exception)
  {
    WeavingResult result = new WeavingResult(Status.TRANSFORMATION_ERROR,
                                             originalBytes);
    result.exception = exception;
    return result;
  }

  /**
   * Returns the outcome of the weaving attempt.
   *
   * @return the weaving status
   */
  public Status getStatus()
  {
    return status;
  }

  public Exception getException()
  {
    return exception;
  }

  /**
   * Returns whether the complete class was successfully woven.
   *
   * @return {@code true} if woven bytes were produced; otherwise
   *         {@code false}
   *
   * @deprecated use {@link #getStatus()} and compare it with
   *             {@link Status#WOVEN}
   */
  @Deprecated
  public boolean isSuccess()
  {
    return status == Status.WOVEN;
  }

  /**
   * Returns the woven bytes when {@link #getStatus()} is {@link Status#WOVEN},
   * or the original bytes otherwise.
   *
   * @return class-file bytes
   */
  public byte[] getBytes()
  {
    return bytes;
  }
}
