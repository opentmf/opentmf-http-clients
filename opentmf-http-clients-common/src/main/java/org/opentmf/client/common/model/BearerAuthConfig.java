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

@Validated
@Getter
@Setter
public class BearerAuthConfig {

  private boolean useMock = false;

  @NotNull
  private URI tokenUrl;

  private String clientId;

  private String clientSecret;

  @NotEmpty
  private String tokenField = "access_token";

  @NotEmpty
  private String expiresInField = "expires_in";

  /**
   * Field name in form-data that specifies the username. Used as part of the cache key.
   */
  @NotEmpty
  private String usernameField = "username";

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
