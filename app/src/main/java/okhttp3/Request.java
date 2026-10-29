package okhttp3;
public final class Request {
    public static final class Builder {
        public Builder() { }
        public Builder url(String url) { return this; }
        public Builder post(RequestBody body) { return this; }
        public Builder addHeader(String name, String value) { return this; }
        public Request build() { return null; }
    }
}
