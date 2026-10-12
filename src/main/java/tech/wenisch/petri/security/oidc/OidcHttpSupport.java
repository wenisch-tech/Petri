package tech.wenisch.petri.security.oidc;

import java.net.http.HttpClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tech.wenisch.petri.config.TlsSettings;

/**
 * Wires the HTTP calls Spring Security makes to an identity provider. They only deviate from the
 * defaults when {@code IGNORE_TLS} or {@code OIDC_INSECURE_SKIP_TLS_VERIFY} is enabled.
 */
@Component
public class OidcHttpSupport {
  private static final Logger log = LoggerFactory.getLogger(OidcHttpSupport.class);

  private final boolean insecure;

  public OidcHttpSupport(OidcProperties properties, TlsSettings tls) {
    this.insecure = tls.ignoreTls() || properties.isInsecureSkipTlsVerify();
    if (insecure && !tls.ignoreTls()) {
      // Covers OIDC discovery and JWKS retrieval, which go through HttpsURLConnection.
      TlsSettings.installHttpsUrlConnectionDefaults();
      log.warn("OIDC_INSECURE_SKIP_TLS_VERIFY is enabled: TLS certificate and hostname "
          + "verification are disabled for OIDC provider connections. Do not use this in production.");
    }
  }

  public boolean insecure() {
    return insecure;
  }

  /** Fetches the discovery document with the trust-all client; null when verification is on. */
  Map<String, Object> discover(String issuerUri) {
    String base = issuerUri.endsWith("/") ? issuerUri : issuerUri + "/";
    String url = base + ".well-known/openid-configuration";
    Map<String, Object> document = insecureRestClient().get().uri(url).retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    if (document == null) {
      throw new IllegalStateException("OIDC discovery document at " + url + " was empty");
    }
    return document;
  }

  /** Returns the token client to register, or null to keep Spring Security's default. */
  public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient() {
    if (!insecure) return null;
    var client = new RestClientAuthorizationCodeTokenResponseClient();
    // setRestClient replaces the default converters wholesale, so restore them here.
    client.setRestClient(RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory(insecureHttpClient()))
        .configureMessageConverters(converters -> {
          converters.addCustomConverter(new FormHttpMessageConverter());
          converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
        })
        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
        .build());
    return client;
  }

  public OidcUserService oidcUserService() {
    OidcUserService service = new OidcUserService();
    if (insecure) {
      DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
      RestTemplate restTemplate =
          new RestTemplate(new JdkClientHttpRequestFactory(insecureHttpClient()));
      restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
      delegate.setRestOperations(restTemplate);
      service.setOauth2UserService(delegate);
    }
    return service;
  }

  private RestClient insecureRestClient() {
    return insecure
        ? RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(insecureHttpClient())).build()
        : RestClient.create();
  }

  private static HttpClient insecureHttpClient() {
    return TlsSettings.insecure(HttpClient.newBuilder()).build();
  }
}
