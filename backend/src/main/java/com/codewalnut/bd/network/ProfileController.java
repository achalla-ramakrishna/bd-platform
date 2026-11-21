package com.codewalnut.bd.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.NetworkQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Each team member's GTN profile page, and each person's link with what it reported (ADR-0019). */
@RestController
@RequestMapping("/api/v1/network")
class ProfileController {

    private final ProfileService profiles;
    private final NetworkQueries queries;
    private final NetworkService network;
    private final PublicUrl publicUrl;
    private final CurrentUserProvider currentUser;
    private final BookingService booking;

    ProfileController(ProfileService profiles, NetworkQueries queries, NetworkService network, PublicUrl publicUrl,
                      CurrentUserProvider currentUser, BookingService booking) {
        this.booking = booking;
        this.profiles = profiles;
        this.queries = queries;
        this.network = network;
        this.publicUrl = publicUrl;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    ProfileService.Page mine() {
        return profiles.of(currentUser.get().id());
    }

    @PutMapping("/profile")
    ProfileService.Page save(@RequestBody ProfileService.Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return profiles.save(in, user);
    }

    /** The raw video file as the body (not a form), so the app's 5 MB form limit stays as it is. */
    @PutMapping("/profile/video")
    ProfileService.Page video(@RequestHeader(value = "Content-Type", required = false) String type, HttpServletRequest req)
            throws IOException {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return profiles.uploadVideo(type, req.getInputStream(), user);
    }

    @DeleteMapping("/profile/video")
    ProfileService.Page deleteVideo() {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return profiles.deleteVideo(user);
    }

    @PutMapping("/profile/captions")
    ProfileService.Page captions(HttpServletRequest req) throws IOException {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        byte[] body = req.getInputStream().readNBytes(200_001);
        return profiles.captions(new String(body, StandardCharsets.UTF_8), user);
    }

    @GetMapping("/profile/video")
    void myVideo(HttpServletRequest req, HttpServletResponse res) throws IOException {
        var video = profiles.video(currentUser.get().id());
        if (video.isEmpty()) {
            res.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        PublicProfileController.stream(profiles, video.get(), req, res);
    }

    @GetMapping("/profile/captions.vtt")
    void myCaptions(HttpServletResponse res) throws IOException {
        ProfileService.Page page = profiles.of(currentUser.get().id());
        var vtt = page.id() == 0 ? java.util.Optional.<String>empty() : profiles.captionsOf(page.id());
        if (vtt.isEmpty()) {
            res.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        res.setContentType("text/vtt;charset=UTF-8");
        res.getWriter().write(vtt.get());
    }

    /** My page as people see it, greeting an example name; nothing is recorded. */
    @GetMapping("/profile/preview")
    void preview(HttpServletResponse res) throws IOException {
        CurrentUser user = currentUser.get();
        ProfileService.Page page = profiles.of(user.id());
        String nonce = PublicProfileController.nonce();
        PublicProfileController.headers(res, nonce);
        res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write(ProfileHtml.page(new ProfileService.View(0, 0, "there", page), "/api/v1/network/profile",
            page.videoBytes() == null ? null : "/api/v1/network/profile/video",
            page.hasCaptions() ? "/api/v1/network/profile/captions.vtt" : null, true, nonce, booking.calendarBooking(page)));
    }

    /** The person's link (made now if needed) and what their page reported. */
    @GetMapping("/tracks/{id}/profile")
    ProfileService.Stats stats(@PathVariable long id) {
        currentUser.get();
        NetworkQueries.Track t = queries.get(id, network.today());
        return profiles.stats(t.connectionId(), publicUrl.base());
    }

    @PostMapping("/tracks/{id}/profile")
    ProfileService.Stats link(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        NetworkQueries.Track t = queries.get(id, network.today());
        profiles.token(t.connectionId(), t.ownerId());
        return profiles.stats(t.connectionId(), publicUrl.base());
    }
}
