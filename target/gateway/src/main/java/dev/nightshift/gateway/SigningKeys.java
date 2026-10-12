package dev.nightshift.gateway;

import dev.nightshift.common.switches.FaultSwitches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Request signing key lookup. When the key-set refresh is skipped (hidden switch
 * {@code gateway.jwks-refresh-skipped}, scenario S08's red herring) the gateway keeps using the
 * cached keys, which is harmless, but warns on every request.
 */
@Component
public class SigningKeys {

    static final String SWITCH = "gateway.jwks-refresh-skipped";
    private static final String CACHED_KID = "k-2026-09";

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final FaultSwitches switches;

    public SigningKeys(FaultSwitches switches) {
        this.switches = switches;
    }

    public String currentKeyId() {
        if (switches.isOn(SWITCH)) {
            log.warn("JWKS refresh skipped, using cached signing keys (kid={})", CACHED_KID);
        }
        return CACHED_KID;
    }
}
