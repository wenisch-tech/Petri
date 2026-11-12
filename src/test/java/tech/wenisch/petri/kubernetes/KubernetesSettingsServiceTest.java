package tech.wenisch.petri.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.kubernetes.ClusterConfig.AuthMode;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@Transactional
class KubernetesSettingsServiceTest {
  private static final String KUBECONFIG =
      "apiVersion: v1\nclusters:\n- name: c\n  cluster: {server: 'https://k8s.test:6443'}\nkind: Config\n";

  @Autowired KubernetesSettingsService service;

  private static KubernetesSettingsService.Save instance(
      String mode, String kubeconfig, String url, String token) {
    return new KubernetesSettingsService.Save(false, false, mode, kubeconfig, false, url, token,
        false, null, false, "petri-workspaces", "registry.test/ws:1", "20Gi", "local-path", null,
        null, null, "4Gi", 1000L, 1000L, 30);
  }

  private static KubernetesSettingsService.Save project(boolean enabled, boolean override) {
    return new KubernetesSettingsService.Save(enabled, override, null, null, false, null, null,
        false, null, false, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void theKubeconfigIsEncryptedAndNeverHandedBackAndABlankFieldKeepsIt() {
    service.saveInstance(instance("KUBECONFIG", KUBECONFIG, null, null), "admin");
    KubernetesWorkspaceConfig stored = service.instance();
    assertThat(stored.isKubeconfigSet()).isTrue();
    assertThat(stored.getKubeconfigCiphertext()).startsWith("v1:").doesNotContain("k8s.test");

    service.saveInstance(instance("KUBECONFIG", "", null, null), "admin");
    assertThat(service.instance().isKubeconfigSet()).isTrue();
    assertThat(service.clusterFor(null).kubeconfig()).isEqualTo(KUBECONFIG.strip());

    var clear = new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, true, null,
        null, false, null, false, "ns", "img", null, null, null, null, null, null, null, null, null);
    service.saveInstance(clear, "admin");
    assertThat(service.instance().isKubeconfigSet()).isFalse();
    assertThat(service.clusterFor(null).authMode()).isEqualTo(AuthMode.IN_CLUSTER);
  }

  @Test
  void aTokenNeedsAUrlAndTheCaMustBeCertificates() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.saveInstance(instance("TOKEN", null, "https://k8s.test:6443", null), "admin"));
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.saveInstance(instance("TOKEN", null, "ftp://k8s.test", "t"), "admin"));
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.saveInstance(instance("TOKEN", null, "https://user:pw@k8s.test", "t"), "admin"));
    var badCa = new KubernetesSettingsService.Save(false, false, "TOKEN", null, false,
        "https://k8s.test:6443", "t", false, "not a certificate", false, "ns", "img", null, null,
        null, null, null, null, null, null, null);
    assertThatIllegalArgumentException().isThrownBy(() -> service.saveInstance(badCa, "admin"));

    service.saveInstance(instance("TOKEN", null, "https://k8s.test:6443/", "abc"), "admin");
    ClusterConfig cluster = service.clusterFor(null);
    assertThat(cluster.apiServerUrl()).isEqualTo("https://k8s.test:6443");
    assertThat(cluster.token()).isEqualTo("abc");
    assertThat(cluster.toString()).doesNotContain("abc");
  }

  @Test
  void fieldsAreValidated() {
    for (var bad : new KubernetesSettingsService.Save[] {
        withNamespace("Not_Valid"), withImage("has space"), withImage("-flag"),
        quantity("lots"), idle(0), idle(20_000)}) {
      assertThatIllegalArgumentException().isThrownBy(() -> service.saveInstance(bad, "admin"));
    }
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.saveInstance(instance("KUBECONFIG", "no clusters here", null, null), "admin"));
  }

  private static KubernetesSettingsService.Save withNamespace(String namespace) {
    return new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, false, null, null,
        false, null, false, namespace, "img", null, null, null, null, null, null, null, null, null);
  }

  private static KubernetesSettingsService.Save withImage(String image) {
    return new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, false, null, null,
        false, null, false, "ns", image, null, null, null, null, null, null, null, null, null);
  }

  private static KubernetesSettingsService.Save quantity(String value) {
    return new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, false, null, null,
        false, null, false, "ns", "img", value, null, null, null, null, null, null, null, null);
  }

  private static KubernetesSettingsService.Save idle(int minutes) {
    return new KubernetesSettingsService.Save(false, false, "IN_CLUSTER", null, false, null, null,
        false, null, false, "ns", "img", null, null, null, null, null, null, null, null, minutes);
  }

  @Test
  void aProjectInheritsTheInstanceDefaultsAndOverridesIndividualValues() {
    service.saveInstance(instance("KUBECONFIG", KUBECONFIG, null, null), "admin");
    UUID projectId = UUID.randomUUID();
    var own = new KubernetesSettingsService.Save(true, false, null, null, false, null, null, false,
        null, false, null, "registry.test/other:2", null, null, null, null, null, null, 2000L, null, 5);
    service.saveProject(projectId, own, "owner");

    assertThat(service.enabled(projectId)).isTrue();
    EffectiveSettings effective = service.effective(projectId);
    assertThat(effective.cluster().authMode()).isEqualTo(AuthMode.KUBECONFIG);
    PodSettings pod = effective.pod();
    assertThat(pod.namespace()).isEqualTo("petri-workspaces");
    assertThat(pod.image()).isEqualTo("registry.test/other:2");
    assertThat(pod.pvcSize()).isEqualTo("20Gi");
    assertThat(pod.storageClass()).isEqualTo("local-path");
    assertThat(pod.memoryLimit()).isEqualTo("4Gi");
    assertThat(pod.runAsUser()).isEqualTo(2000L);
    assertThat(pod.fsGroup()).isEqualTo(1000L);
    assertThat(pod.idleTimeoutMinutes()).isEqualTo(5);
  }

  @Test
  void aProjectCanUseItsOwnClusterAndTheSecretsStayPerScope() {
    service.saveInstance(instance("KUBECONFIG", KUBECONFIG, null, null), "admin");
    UUID projectId = UUID.randomUUID();
    var own = new KubernetesSettingsService.Save(true, true, "TOKEN", null, false,
        "https://own.test:6443", "own-token", false, null, true, "ns", "img", null, null, null,
        null, null, null, null, null, null);
    service.saveProject(projectId, own, "owner");

    ClusterConfig cluster = service.effective(projectId).cluster();
    assertThat(cluster.authMode()).isEqualTo(AuthMode.TOKEN);
    assertThat(cluster.token()).isEqualTo("own-token");
    assertThat(cluster.insecure()).isTrue();
    assertThat(service.clusterFor(null).authMode()).isEqualTo(AuthMode.KUBECONFIG);

    service.saveProject(projectId, project(true, false), "owner");
    assertThat(service.project(projectId).orElseThrow().isTokenSet()).isFalse();
    assertThat(service.effective(projectId).cluster().authMode()).isEqualTo(AuthMode.KUBECONFIG);
  }

  @Test
  void enablingNeedsAClusterAnImageAndANamespace() {
    UUID projectId = UUID.randomUUID();
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.saveProject(projectId, project(true, false), "owner"));
    assertThat(service.enabled(projectId)).isFalse();
    assertThatIllegalStateException().isThrownBy(() -> service.effective(projectId));
  }

  @Test
  void theBundledToolsDefaultToFallbackAndAProjectInheritsOrOverridesTheInstanceChoice() {
    service.saveInstance(instance("KUBECONFIG", KUBECONFIG, null, null), "admin");
    UUID projectId = UUID.randomUUID();
    service.saveProject(projectId, project(true, false), "owner");
    assertThat(service.effective(projectId).pod().toolchain()).isEqualTo(ToolchainMode.FALLBACK);

    var offByDefault = new KubernetesSettingsService.Save(false, false, "KUBECONFIG", "", false, null,
        null, false, null, false, "petri-workspaces", "registry.test/ws:1", "20Gi", "local-path", null,
        null, null, "4Gi", 1000L, 1000L, 30, "off");
    service.saveInstance(offByDefault, "admin");
    assertThat(service.effective(projectId).pod().toolchain()).isEqualTo(ToolchainMode.OFF);

    var own = new KubernetesSettingsService.Save(true, false, null, null, false, null, null, false,
        null, false, null, null, null, null, null, null, null, null, null, null, null, " Prefer ");
    service.saveProject(projectId, own, "owner");
    assertThat(service.effective(projectId).pod().toolchain()).isEqualTo(ToolchainMode.PREFER);
    assertThat(service.project(projectId).orElseThrow().getToolchainMode()).isEqualTo("PREFER");

    var invalid = new KubernetesSettingsService.Save(true, false, null, null, false, null, null, false,
        null, false, null, null, null, null, null, null, null, null, null, null, null, "sometimes");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.saveProject(projectId, invalid, "owner"))
        .withMessageContaining("off, fallback or prefer");
  }
}
