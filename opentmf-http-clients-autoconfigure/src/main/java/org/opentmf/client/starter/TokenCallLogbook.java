package org.opentmf.client.starter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.zalando.logbook.ForwardingHttpResponse;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Strategy;

/**
 * The {@link Logbook} of a client's token calls: the application's own, except that the token
 * endpoint's response body is never handed to it. A {@value #WITHHELD} marker stands in its
 * place; the request, the status and the headers are logged exactly as the application's
 * Logbook logs them. An identity provider may echo the request form, which can hold the client
 * secret, a password or a refresh token, and a token answer holds the token itself.
 *
 * <p>Used only for the token calls of a client; the client's own calls keep the application's
 * Logbook. {@code bearer-auth.log-response-body: true} gives the token calls the application's
 * Logbook unchanged. The body the caller reads is untouched: only the copy Logbook formats is
 * replaced.</p>
 */
public final class TokenCallLogbook implements Logbook {

  /** What a token call's log line shows in place of a non-empty response body. */
  public static final String WITHHELD =
      "<token response body withheld; bearer-auth.log-response-body: true logs it>";

  private static final byte[] WITHHELD_BYTES = WITHHELD.getBytes(StandardCharsets.UTF_8);

  private final Logbook delegate;

  public TokenCallLogbook(Logbook delegate) {
    this.delegate = delegate;
  }

  @Override
  public RequestWritingStage process(HttpRequest request) throws IOException {
    return withheld(delegate.process(request));
  }

  @Override
  public RequestWritingStage process(HttpRequest request, Strategy strategy) throws IOException {
    return withheld(delegate.process(request, strategy));
  }

  private static RequestWritingStage withheld(RequestWritingStage stage) {
    return new RequestWritingStage() {
      @Override
      public ResponseProcessingStage write() throws IOException {
        var written = stage.write();
        return response -> written.process(new BodyWithheld(response));
      }

      @Override
      public ResponseWritingStage process(HttpResponse response) throws IOException {
        return stage.process(new BodyWithheld(response));
      }
    };
  }

  /** The response as Logbook sees it: everything but a non-empty body, which is the marker. */
  private record BodyWithheld(HttpResponse delegate) implements ForwardingHttpResponse {

    @Override
    public byte[] getBody() throws IOException {
      return delegate.getBody().length == 0 ? delegate.getBody() : WITHHELD_BYTES.clone();
    }

    @Override
    public String getBodyAsString() throws IOException {
      return delegate.getBody().length == 0 ? "" : WITHHELD;
    }

    @Override
    public HttpResponse withBody() throws IOException {
      return new BodyWithheld(delegate.withBody());
    }

    @Override
    public HttpResponse withoutBody() {
      return new BodyWithheld(delegate.withoutBody());
    }
  }
}
