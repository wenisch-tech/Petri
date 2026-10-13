package tech.wenisch.petri.web;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** About Petri: version, license and the runtime it is running on. */
@Controller
public class AboutController {
  private final DataSource dataSource;

  public AboutController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @GetMapping("/about")
  String about(Model model) {
    model.addAttribute("javaVersion", System.getProperty("java.version"));
    model.addAttribute("javaVendor", System.getProperty("java.vendor"));
    model.addAttribute("springBootVersion", SpringBootVersion.getVersion());
    model.addAttribute("startedAt",
        Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    model.addAttribute("database", database());
    return "about";
  }

  private String database() {
    try (var connection = dataSource.getConnection()) {
      var meta = connection.getMetaData();
      return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
    } catch (Exception exception) {
      return "unavailable";
    }
  }
}
