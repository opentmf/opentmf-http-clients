package org.opentmf.client.common.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP Basic authentication credentials, attached to every request of the owning client.
 */
@Validated
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BasicAuthConfig {

  /**
   * User name for HTTP Basic authentication.
   */
  @NotBlank
  private String username;

  /**
   * Password for HTTP Basic authentication.
   */
  @NotBlank
  private String password;

  /**
   * Character set for encoding username and password. Defaults to US-ASCII per RFC 7617.
   */
  private String charset = "US-ASCII";
}
