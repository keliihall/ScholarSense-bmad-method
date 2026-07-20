package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;

final class BrowserSessionBinding {
    static final String ATTRIBUTE = "identity.browser-binding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BrowserSessionBinding() {}

    static String getOrCreate(HttpSession session) {
        Object existing = session.getAttribute(ATTRIBUTE);
        if (existing instanceof String value && value.matches("bb_[A-Za-z0-9_-]{43}")) {
            return value;
        }
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String value = "bb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        session.setAttribute(ATTRIBUTE, value);
        return value;
    }
}
