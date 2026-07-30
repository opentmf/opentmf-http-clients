package org.opentmf.client.common.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
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
}
