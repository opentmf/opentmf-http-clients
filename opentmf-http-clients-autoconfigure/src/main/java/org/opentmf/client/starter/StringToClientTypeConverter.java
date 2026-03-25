package org.opentmf.client.starter;

import org.opentmf.client.common.model.ClientType;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@ConfigurationPropertiesBinding
public class StringToClientTypeConverter implements Converter<String, ClientType> {

  @Override
  public ClientType convert(String source) {
    return ClientType.fromString(source);
  }
}
