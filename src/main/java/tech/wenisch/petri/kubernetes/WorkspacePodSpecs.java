package tech.wenisch.petri.kubernetes;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds the Kubernetes objects of a workspace. Pure functions, so they are easy to verify. */
final class WorkspacePodSpecs {
  static final int AGENT_PORT = 7777;
  static final String CONTAINER = "workspace";
  static final String WORKSPACE_ROOT = "/petri/workspace";
  static final String BIN_DIR = "/petri/bin";
  static final String AGENT_PATH = BIN_DIR + "/petri-workspace-agent";
  static final String ASKPASS_PATH = BIN_DIR + "/askpass";
  static final String TOKEN_KEY = "token";

  /** Waits for the copied agent, then becomes it; works with any image that has a POSIX shell. */
  static final String BOOT_COMMAND =
      "while [ ! -x " + AGENT_PATH + " ]; do sleep 1; done; exec " + AGENT_PATH + " serve";

  private WorkspacePodSpecs() {}

  static Map<String, String> labels(UUID projectId) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("app.kubernetes.io/name", "petri-workspace");
    labels.put("app.kubernetes.io/managed-by", "petri");
    labels.put("petri.wenisch.tech/project", projectId.toString());
    return labels;
  }

  static Secret secret(WorkspacePodState state, String token) {
    return new SecretBuilder()
        .withNewMetadata()
            .withName(state.getSecretName()).withNamespace(state.getNamespace())
            .withLabels(labels(state.getProjectId()))
        .endMetadata()
        .withType("Opaque")
        .addToData(TOKEN_KEY, Base64.getEncoder().encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .build();
  }

  static PersistentVolumeClaim pvc(WorkspacePodState state, PodSettings settings) {
    return new PersistentVolumeClaimBuilder()
        .withNewMetadata()
            .withName(state.getPvcName()).withNamespace(state.getNamespace())
            .withLabels(labels(state.getProjectId()))
        .endMetadata()
        .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withStorageClassName(settings.storageClass())
            .withNewResources()
                .addToRequests("storage", new Quantity(settings.pvcSize()))
            .endResources()
        .endSpec()
        .build();
  }

  static Pod pod(WorkspacePodState state, PodSettings settings) {
    var builder = new PodBuilder()
        .withNewMetadata()
            .withName(state.getPodName()).withNamespace(state.getNamespace())
            .withLabels(labels(state.getProjectId()))
        .endMetadata()
        .withNewSpec()
            .withRestartPolicy("Always")
            .withAutomountServiceAccountToken(false)
            .withEnableServiceLinks(false)
            .withNewSecurityContext()
                .withRunAsUser(settings.runAsUser())
                .withFsGroup(settings.fsGroup())
                .withRunAsNonRoot(settings.runAsUser() != null && settings.runAsUser() > 0 ? true : null)
            .endSecurityContext()
            .addNewContainer()
                .withName(CONTAINER)
                .withImage(settings.image())
                .withCommand("sh", "-c", BOOT_COMMAND)
                .addNewEnv()
                    .withName("PETRI_AGENT_TOKEN")
                    .withNewValueFrom().withNewSecretKeyRef()
                        .withName(state.getSecretName()).withKey(TOKEN_KEY)
                    .endSecretKeyRef().endValueFrom()
                .endEnv()
                .addNewEnv().withName("PETRI_WORKSPACE_ROOT").withValue(WORKSPACE_ROOT).endEnv()
                .addNewPort().withName("agent").withContainerPort(AGENT_PORT).endPort()
                .withNewReadinessProbe()
                    .withNewHttpGet().withPath("/v1/healthz").withPort(new IntOrString("agent")).endHttpGet()
                    .withPeriodSeconds(5).withFailureThreshold(3)
                .endReadinessProbe()
                .withNewSecurityContext()
                    .withAllowPrivilegeEscalation(false)
                    .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .endSecurityContext()
                .addNewVolumeMount().withName("workspace").withMountPath(WORKSPACE_ROOT).endVolumeMount()
                .addNewVolumeMount().withName("bin").withMountPath(BIN_DIR).endVolumeMount()
            .endContainer()
            .addNewVolume()
                .withName("workspace")
                .withNewPersistentVolumeClaim().withClaimName(state.getPvcName()).endPersistentVolumeClaim()
            .endVolume()
            .addNewVolume().withName("bin").withNewEmptyDir().endEmptyDir().endVolume()
        .endSpec();
    Map<String, Quantity> requests = new LinkedHashMap<>();
    Map<String, Quantity> limits = new LinkedHashMap<>();
    put(requests, "cpu", settings.cpuRequest());
    put(requests, "memory", settings.memoryRequest());
    put(limits, "cpu", settings.cpuLimit());
    put(limits, "memory", settings.memoryLimit());
    Pod pod = builder.build();
    if (!requests.isEmpty() || !limits.isEmpty()) {
      pod.getSpec().getContainers().get(0).setResources(
          new io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder()
              .withRequests(requests).withLimits(limits).build());
    }
    return pod;
  }

  private static void put(Map<String, Quantity> target, String key, String value) {
    if (value != null) target.put(key, new Quantity(value));
  }

  /** The script that stores the streamed agent binary, exactly {@code size} bytes of it. */
  static String installScript(long size) {
    return "mkdir -p " + BIN_DIR + " && head -c " + size + " > " + BIN_DIR + "/.agent.tmp"
        + " && chmod 755 " + BIN_DIR + "/.agent.tmp"
        + " && mv " + BIN_DIR + "/.agent.tmp " + AGENT_PATH
        + " && printf '#!/bin/sh\\nexec " + AGENT_PATH + " askpass \"$@\"\\n' > " + ASKPASS_PATH
        + " && chmod 755 " + ASKPASS_PATH;
  }
}
