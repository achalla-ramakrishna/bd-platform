package com.codewalnut.bd.network;

import static com.codewalnut.bd.network.ProfileService.escape;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The public profile page (ADR-0019): video first, a short intro, proof points and one-tap actions. Self-contained
 * HTML with escaped content and a small script that reports what happened on the page; a preview reports nothing.
 */
final class ProfileHtml {

    private ProfileHtml() {}

    /**
     * {@code base}: where the page's video, captions and events live (e.g. /p/{token}); {@code preview}: the owner
     * looking at their own page, nothing is recorded.
     */
    static String page(ProfileService.View view, String base, String videoUrl, String captionsUrl, boolean preview, String nonce) {
        ProfileService.Page p = view.page();
        String owner = p.ownerName() == null ? "CodeWalnut" : p.ownerName();
        String ownerFirst = Suggestions.firstName(owner);
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<meta name=\"robots\" content=\"noindex, nofollow\">")
            .append("<title>").append(escape(p.headline())).append("</title>")
            .append("<meta property=\"og:title\" content=\"").append(escape(p.headline())).append("\">")
            .append("<meta property=\"og:description\" content=\"A short intro from ").append(escape(owner)).append(" at CodeWalnut\">")
            .append("<style>").append(CSS).append("</style></head><body><main>");
        if (preview) {
            h.append("<p class=\"preview\">Preview: this is what people see. Nothing is recorded while you look.</p>");
        }
        if (view.firstName() != null && !view.firstName().isBlank()) {
            h.append("<p class=\"hello\">Hi ").append(escape(view.firstName())).append(",</p>");
        }
        h.append("<h1>").append(escape(p.headline())).append("</h1>");
        if (videoUrl != null) {
            h.append("<video id=\"intro\" controls playsinline preload=\"metadata\" src=\"").append(escape(videoUrl)).append("\">");
            if (captionsUrl != null) {
                h.append("<track kind=\"captions\" srclang=\"en\" label=\"English\" default src=\"").append(escape(captionsUrl)).append("\">");
            }
            h.append("</video>");
        }
        if (p.intro() != null && !p.intro().isBlank()) {
            for (String para : p.intro().split("\\n\\s*\\n")) {
                h.append("<p class=\"intro\">").append(escape(para.strip())).append("</p>");
            }
        }
        if (!p.proofPoints().isEmpty()) {
            h.append("<ul class=\"proof\">");
            p.proofPoints().forEach(x -> h.append("<li>").append(escape(x)).append("</li>"));
            h.append("</ul>");
        }
        h.append("<div class=\"cta\">");
        if (p.bookingUrl() != null) {
            h.append("<a class=\"btn primary\" data-cta=\"BOOK\" target=\"_blank\" rel=\"noopener\" href=\"").append(escape(p.bookingUrl()))
                .append("\">Book a 20-minute call</a>");
        }
        if (p.whatsapp() != null) {
            String text = URLEncoder.encode("Hi " + ownerFirst + ", saw your page. ", StandardCharsets.UTF_8).replace("+", "%20");
            h.append("<a class=\"btn\" data-cta=\"WHATSAPP\" target=\"_blank\" rel=\"noopener\" href=\"https://wa.me/")
                .append(escape(p.whatsapp())).append("?text=").append(text).append("\">Chat on WhatsApp</a>");
        }
        if (p.interestedEnabled()) {
            h.append("<button type=\"button\" class=\"btn\" id=\"interested\">Interested, tell me more</button>");
        }
        h.append("</div><p id=\"thanks\" class=\"thanks\" hidden>Thank you! ").append(escape(ownerFirst))
            .append(" will be in touch shortly.</p>")
            .append("<footer><p>").append(escape(owner)).append(" · CodeWalnut · <a href=\"https://www.codewalnut.com\" target=\"_blank\" rel=\"noopener\">codewalnut.com</a></p>")
            .append("<p class=\"note\">This page tells ").append(escape(ownerFirst))
            .append(" when it is opened, how much of the video is watched and which button is clicked, so they know it reached you."
                + " No cookies, nothing about your device.</p></footer></main>");
        h.append("<script nonce=\"").append(nonce).append("\">(function(){")
            .append("var P=").append(preview).append(",E=").append(js(base + "/events")).append(";")
            .append("function post(k,v){if(P)return;try{fetch(E,{method:'POST',headers:{'Content-Type':'application/json'},")
            .append("body:JSON.stringify({kind:k,value:v||null}),keepalive:true}).catch(function(){});}catch(e){}}")
            .append("post('VISIT');")
            .append("var v=document.getElementById('intro');if(v){var played=false,sent={};")
            .append("v.addEventListener('play',function(){if(!played){played=true;post('VIDEO_PLAY');}});")
            .append("function mark(){if(!v.duration||!isFinite(v.duration))return;var pct=v.currentTime/v.duration*100;")
            .append("[25,50,75,100].forEach(function(m){if(!sent[m]&&(pct>=m||(m===100&&pct>=97))){sent[m]=true;post('VIDEO_PROGRESS',String(m));}});}")
            .append("v.addEventListener('timeupdate',mark);v.addEventListener('ended',function(){if(!sent[100]){sent[100]=true;post('VIDEO_PROGRESS','100');}});}")
            .append("document.querySelectorAll('[data-cta]').forEach(function(a){a.addEventListener('click',function(){post('CTA_CLICK',a.getAttribute('data-cta'));});});")
            .append("var i=document.getElementById('interested');if(i){i.addEventListener('click',function(){post('INTERESTED');")
            .append("i.disabled=true;document.getElementById('thanks').hidden=false;});}")
            .append("})();</script></body></html>");
        return h.toString();
    }

    static String gone() {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<meta name=\"robots\" content=\"noindex\"><title>CodeWalnut</title><style>" + CSS + "</style></head><body><main>"
            + "<h1>This link is no longer active</h1><p class=\"intro\">Visit <a href=\"https://www.codewalnut.com\">codewalnut.com</a> to learn "
            + "about CodeWalnut.</p></main></body></html>";
    }

    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c") + "'";
    }

    private static final String CSS = """
        :root{--bg:#f7f8fd;--fg:#141a4d;--muted:#4a5078;--accent:#3550e6;--line:#d6daf3;--card:#fff}
        @media (prefers-color-scheme:dark){:root{--bg:#0f1330;--fg:#eef0fb;--muted:#b9bfe3;--accent:#9dacff;--line:#2c3470;--card:#161b44}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        main{max-width:720px;margin:0 auto;padding:28px 16px 40px}
        h1{font-size:clamp(26px,5vw,36px);line-height:1.2;margin:4px 0 18px}
        .hello{color:var(--muted);margin:0}.preview{background:#fff3cd;color:#664d03;padding:8px 12px;border-radius:8px;font-size:14px}
        video{width:100%;border-radius:14px;background:#000;display:block;margin:0 0 20px;aspect-ratio:16/9}
        .intro{margin:0 0 14px}.proof{padding:0;margin:18px 0;list-style:none;display:grid;gap:10px}
        .proof li{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px;font-weight:600}
        .cta{display:flex;flex-wrap:wrap;gap:10px;margin:22px 0}
        .btn{font:inherit;font-weight:600;padding:12px 18px;border-radius:10px;border:1px solid var(--accent);color:var(--accent);
          background:transparent;text-decoration:none;cursor:pointer;flex:1 1 200px;text-align:center}
        .btn.primary{background:var(--accent);color:#fff}.btn:disabled{opacity:.6;cursor:default}
        .thanks{font-weight:600;color:var(--accent)}footer{margin-top:28px;border-top:1px solid var(--line);padding-top:12px;color:var(--muted);font-size:14px}
        footer a{color:var(--accent)}.note{font-size:12px}
        """;
}
