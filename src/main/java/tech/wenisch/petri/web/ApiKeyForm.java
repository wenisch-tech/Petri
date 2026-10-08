package tech.wenisch.petri.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public class ApiKeyForm {
  @NotBlank
  @Size(max = 120)
  private String name;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate expiresOn;

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public LocalDate getExpiresOn() { return expiresOn; }
  public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
}
