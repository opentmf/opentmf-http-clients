package org.opentmf.client.common.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth2 bearer-token configuration for a client. Tokens are retrieved from {@link #tokenUrl} and
 * cached per (token URL, scope, username) with a TTL derived from the token response's
 * {@code expires_in}.
 */
@Validated
@Getter
@Setter
public class BearerAuthConfig {

  /**
   * When true, a mock token service returning a static demo JWT is registered instead of calling
   * the token endpoint. Intended for tests and local development only. Default false.
   */
  private boolean useMock = false;

  /**
   * URL of the OAuth2 token endpoint.
   */
  @NotNull
  private URI tokenUrl;

  /**
   * OAuth2 client id. When both client-id and client-secret are set, they are sent to the token
   * endpoint as HTTP Basic authentication; otherwise supply the credentials via
   * {@link #formData}.
   */
  private String clientId;

  /**
   * OAuth2 client secret, sent together with {@link #clientId} as HTTP Basic authentication to
   * the token endpoint.
   */
  private String clientSecret;

  /**
   * Name of the token-response JSON field that contains the access token.
   * Default {@code access_token}.
   */
  @NotEmpty
  private String tokenField = "access_token";

  /**
   * Name of the token-response JSON field that contains the token lifetime in seconds.
   * Default {@code expires_in}.
   */
  @NotEmpty
  private String expiresInField = "expires_in";

  /**
   * Field name in form-data that specifies the username. Used as part of the cache key.
   */
  @NotEmpty
  private String usernameField = "username";

  /**
   * Form fields posted to the token endpoint, e.g. {@code grant_type}, {@code scope}, or
   * {@code client_id}/{@code client_secret} when not using Basic authentication.
   */
  @NotEmpty
  private Map<@NotBlank String, @NotBlank String> formData;

  /**
   * Headers sent on the token request only, for an identity provider that needs one, e.g. an API
   * gateway's {@code x-api-key}. The client's {@code fixed-headers} never reach the token
   * endpoint; repeat a header here when the token endpoint needs it too. Cannot replace the
   * token request's own {@code Content-Type}, nor its Basic {@code Authorization} when
   * client-id and client-secret are set. Default empty.
   */
  private Map<@NotBlank String, @NotBlank String> headers = new LinkedHashMap<>();

  /**
   * Assumed token lifetime in seconds when the token response does not include an expires_in field.
   * Per RFC 6749, expires_in is recommended but not required.
   */
  @Positive
  private long fallbackExpiresInSeconds = 3600;

  /**
   * Fraction of the token's expires_in to use as cache TTL.
   * Default 0.9 means a token valid for 3600s is cached for 3240s.
   */
  @DecimalMin("0.1")
  @DecimalMax("0.99")
  private double cacheSafetyFactor = 0.9;

  /**
   * The token request's own {@code request-timeout}: the time allowed for obtaining a usable
   * connection to the token endpoint, with the same meaning per client type as the client's
   * {@code request-timeout}. When unset, the token request uses the client's
   * {@code request-timeout}, as it always has. Set it when the client is tuned for a slow
   * backend: an identity provider connects in seconds.
   */
  private @Nullable Duration requestTimeout;

  /**
   * The token request's own {@code response-timeout}: the time to wait for the token endpoint's
   * answer, with the same meaning per client type as the client's key (one deadline for the
   * whole exchange on JDK, silence per read on Apache and Netty). When unset, the token request uses the client's {@code response-timeout}, as it
   * always has. A token request that fails at the transport level (a timeout included) is retried
   * once at once, so an identity provider that does not answer holds a token retrieval for twice
   * this value. Set it when the client is tuned for a slow backend: an identity provider answers
   * in seconds.
   */
  private @Nullable Duration responseTimeout;

  /**
   * The token request's own {@code num-retries}: how many times a retryable status from the token
   * endpoint (e.g. 503) is retried, with the client's {@code retry-wait-duration} and
   * {@code max-retry-after}. When unset, the client's {@code num-retries} applies, as it always
   * has. Set it when the client's {@code num-retries} is tuned for its business calls and should
   * not govern the token retrieval as well.
   */
  @PositiveOrZero
  private @Nullable Integer numRetries;

  /**
   * Opt-in memory of a failed token retrieval: how long a retrieval that failed retryably (a 5xx,
   * 408 or 429 from the token endpoint, or a transport failure, after the token call's own
   * retries) is remembered. Until then every token request for the same token URL, scope and
   * username fails at once with that failure, without a call, so callers do not repeat the
   * retrieval against a failing identity provider. A {@code Retry-After} from the token endpoint
   * lengthens it, up to the client's {@code max-retry-after}. A refused credential (any other
   * 4xx) and an unusable answer are never remembered. The cost: once the identity provider is
   * back, the next token can be retrieved up to this long late. Unset by default: a failure is
   * not remembered, and the next caller retrieves again.
   */
  private @Nullable Duration failureMemory;

  /**
   * Whether the request log (Logbook, with the client's {@code logging-enabled}) shows the token
   * endpoint's response body. Default false: a marker stands in its place, because an identity
   * provider may echo the request form (the client secret, a password, a refresh token) and a
   * token answer holds the token itself; the request, the status and the headers of a token call
   * are logged as for any call. Set it true to debug a token endpoint, never in production.
   */
  private boolean logResponseBody = false;
}
