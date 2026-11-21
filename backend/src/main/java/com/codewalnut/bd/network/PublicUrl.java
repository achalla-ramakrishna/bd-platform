package com.codewalnut.bd.network;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.codewalnut.bd.walnut.WalnutProperties;

/** The app's public address for profile links (ADR-0019): APP_BASE_URL, or the address of the current request. */
@Component
class PublicUrl {

    private final WalnutProperties walnut;

    PublicUrl(WalnutProperties walnut) {
        this.walnut = walnut;
    }

    String base() {
        if (!walnut.baseUrl().isBlank()) {
            return walnut.baseUrl();
        }
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().replaceAll("/+$", "");
        } catch (IllegalStateException e) {
            return "";
        }
    }
}
