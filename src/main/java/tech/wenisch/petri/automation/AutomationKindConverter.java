package tech.wenisch.petri.automation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores an action as its id and reads it back from the catalog. */
@Converter
public class AutomationKindConverter implements AttributeConverter<AutomationKind, String> {
  @Override
  public String convertToDatabaseColumn(AutomationKind kind) {
    return kind == null ? null : kind.id();
  }

  @Override
  public AutomationKind convertToEntityAttribute(String id) {
    return id == null ? null : AutomationKind.lookup(id);
  }
}
