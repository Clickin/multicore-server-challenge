package challenge.servlet;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

final class Async extends AsyncAdapter {
    private final Request request;
    private final Response response;

    Async(Request request, Response response) {
        this.request = request;
        this.response = response;
    }

    public ServletRequest getRequest() {
        return request;
    }

    public ServletResponse getResponse() {
        return response;
    }

    public boolean hasOriginalRequestAndResponse() {
        return true;
    }

    public void complete() {
        request.loop().assertLoopThread();
        request.completed();
        response.finish.run();
    }
}
