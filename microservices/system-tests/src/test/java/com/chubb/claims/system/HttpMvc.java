package com.chubb.claims.system;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * A stand-in for {@code MockMvc} that sends the request over a real HTTP connection to whichever running service the
 * router picks. It takes the same {@code RequestBuilder}s ({@code post(...)}, {@code get(...)}) and returns a
 * {@link ResultActions}, so the original MockMvc test code runs unchanged against the running services.
 */
final class HttpMvc {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Function<String, String> router;

    HttpMvc(Function<String, String> router) {
        this.router = router;
    }

    ResultActions perform(RequestBuilder builder) throws Exception {
        MockHttpServletRequest request = builder.buildRequest(new MockServletContext());
        String path = request.getRequestURI();
        String url = router.apply(path) + path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());

        byte[] body = request.getContentAsByteArray();
        if (body == null) { // no content was set, e.g. on a GET
            body = new byte[0];
        }
        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (request.getContentType() != null) {
            out.header("Content-Type", request.getContentType());
        }
        // A request that sets an Authorization header keeps it (an empty one means "send no credentials at all"); one that
        // does not is given the default identity, which is how the original tests, written before login existed, still run.
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            authorization = SystemUnderTest.get().defaultAuthorization(path);
        }
        if (!authorization.isBlank()) {
            out.header("Authorization", authorization);
        }
        out.method(request.getMethod(), body.length == 0 ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = http.send(out.build(), BodyHandlers.ofByteArray());

        MockHttpServletResponse mock = new MockHttpServletResponse();
        mock.setStatus(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals("content-length") && !lower.equals("transfer-encoding")) {
                values.forEach(value -> mock.addHeader(name, value));
            }
        });
        mock.setCharacterEncoding("UTF-8");
        mock.getOutputStream().write(response.body());
        return new Result(new Captured(request, mock));
    }

    private record Captured(MockHttpServletRequest request, MockHttpServletResponse response) implements MvcResult {
        @Override
        public MockHttpServletRequest getRequest() {
            return request;
        }

        @Override
        public MockHttpServletResponse getResponse() {
            return response;
        }

        @Override
        public Object getHandler() {
            return null;
        }

        @Override
        public HandlerInterceptor[] getInterceptors() {
            return null;
        }

        @Override
        public ModelAndView getModelAndView() {
            return null;
        }

        @Override
        public Exception getResolvedException() {
            return null;
        }

        @Override
        public FlashMap getFlashMap() {
            return null;
        }

        @Override
        public Object getAsyncResult() {
            return null;
        }

        @Override
        public Object getAsyncResult(long timeToWait) {
            return null;
        }
    }

    private record Result(MvcResult result) implements ResultActions {
        @Override
        public ResultActions andExpect(ResultMatcher matcher) throws Exception {
            matcher.match(result);
            return this;
        }

        @Override
        public ResultActions andDo(ResultHandler handler) throws Exception {
            handler.handle(result);
            return this;
        }

        @Override
        public MvcResult andReturn() {
            return result;
        }
    }
}
