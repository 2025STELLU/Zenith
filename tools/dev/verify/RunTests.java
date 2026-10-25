import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/** 离线跑 JUnit 5：Gradle 的 test 任务在本机受依赖解析阻塞，这里直接调 launcher。 */
public class RunTests {
    public static void main(String[] args) {
        LauncherDiscoveryRequest req = request()
                .selectors(selectPackage("net.atomos.zenith"))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(req, listener);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        summary.printFailuresTo(out, 20);

        long failed = summary.getTotalFailureCount();
        System.out.println();
        System.out.println("tests=" + summary.getTestsSucceededCount()
                + " skipped=" + summary.getTestsSkippedCount()
                + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
