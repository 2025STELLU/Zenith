package net.atomos.zenith.api;

/** 极线计算结果。 */
public final class ZenithPolarResult {
    public enum Status { OK, UNSUPPORTED, FAILED }

    private final Status status;
    private final ZenithPolarRequest request;
    private final ZenithPolarTable table;
    private final String message;

    private ZenithPolarResult(Status status, ZenithPolarRequest request,
                              ZenithPolarTable table, String message) {
        this.status = status;
        this.request = request;
        this.table = table;
        this.message = message;
    }

    public static ZenithPolarResult unsupported() {
        return new ZenithPolarResult(Status.UNSUPPORTED, null, null, "No polar provider registered");
    }

    public static ZenithPolarResult ok(ZenithPolarRequest request, ZenithPolarTable table, String info) {
        return new ZenithPolarResult(Status.OK, request, table, info);
    }

    public static ZenithPolarResult failed(ZenithPolarRequest request, String message) {
        return new ZenithPolarResult(Status.FAILED, request, null, message);
    }

    public Status status() { return status; }
    public boolean succeeded() { return status == Status.OK; }
    public boolean available() { return status != Status.UNSUPPORTED; }
    public ZenithPolarRequest request() { return request; }
    public ZenithPolarTable table() { return table; }
    public boolean hasTable() { return table != null; }
    public String message() { return message; }
}
