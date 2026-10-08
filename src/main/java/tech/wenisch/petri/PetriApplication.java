package tech.wenisch.petri;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tech.wenisch.petri.config.PetriProperties;

@SpringBootApplication
@EnableConfigurationProperties(PetriProperties.class)
public class PetriApplication {
  public static void main(String[] args) {
    SpringApplication.run(PetriApplication.class, args);
  }
}

