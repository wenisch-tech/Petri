package tech.wenisch.petri.automation;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Binds the {@code kind} form field to an action by its id. */
@Component
class AutomationKindWebConverter implements Converter<String, AutomationKind> {
  @Override
  public AutomationKind convert(String source) {
    return source == null || source.isBlank() ? null : AutomationKind.valueOf(source.strip());
  }
}
