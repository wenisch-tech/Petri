package tech.wenisch.petri.agent.run;

import java.util.List;

record TestCommand(TestBuildSystem buildSystem, List<String> arguments) {
  TestCommand {
    arguments = List.copyOf(arguments);
  }

  String display() {
    return String.join(" ", arguments);
  }
}
