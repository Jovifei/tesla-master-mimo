import org.junit.Test;

/**
 * Runs the byte-identical independently supplied d119735 Java/Moshi
 * behavior replay against compiled Android classes in BOTH unit variants.
 * Synthetic scoped IDs/dates only; no Tesla, device, network or real data.
 *
 * Fixture blob SHA (preserved verbatim from local evidence branch):
 * 4e4252aa30803a83c171af22ae9fea61ec332323
 */
public final class DisplayProjectionReplayTest {
    @Test
    public void sameIdDetailCurrentMetadataSurvivesActualCompiledOfflineAndMerge() throws Exception {
        DisplayProjectionReplay.main(new String[0]);
    }
}
