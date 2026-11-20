package com.transit.engine.web.controller;

import com.transit.engine.domain.graph.RoutingProfile;
import com.transit.engine.service.GraphService;
import com.transit.engine.service.GraphService.DelayCertificate;
import com.transit.engine.service.GraphService.DetailedRouteResult;
import com.transit.engine.service.GraphService.MeetingSearchResult;
import com.transit.engine.service.GraphService.RadarAnalysisResult;
import com.transit.engine.service.GraphService.ShieldAnalysisResult;
import com.transit.engine.service.GraphService.StopoverSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final GraphService graphService;

    @GetMapping
    public ResponseEntity<DetailedRouteResult> getRoute(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false, defaultValue = "FASTEST") String profile) {

        RoutingProfile routingProfile = RoutingProfile.fromString(profile);
        DetailedRouteResult result = graphService.buildDetailedRoute(
                from.trim().toUpperCase(),
                to.trim().toUpperCase(),
                routingProfile
        );

        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/stopover")
    public ResponseEntity<StopoverSearchResult> getStopovers(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false, defaultValue = "15") int maxDetourMinutes,
            @RequestParam(required = false, defaultValue = "ALL") String category) {

        StopoverSearchResult result = graphService.findStopoverCandidates(
                from.trim().toUpperCase(),
                to.trim().toUpperCase(),
                maxDetourMinutes,
                category
        );

        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/meeting")
    public ResponseEntity<MeetingSearchResult> getMeetingPoints(
            @RequestParam List<String> origins,
            @RequestParam(required = false, defaultValue = "MIN_MAX") String strategy) {

        MeetingSearchResult result = graphService.findFairMeetingPoints(origins, strategy);

        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/radar")
    public ResponseEntity<RadarAnalysisResult> getRadarAnalysis() {
        return ResponseEntity.ok(graphService.buildRadarAnalysis());
    }

    @GetMapping("/shield")
    public ResponseEntity<ShieldAnalysisResult> getShieldAnalysis(
            @RequestParam String from,
            @RequestParam String to) {

        ShieldAnalysisResult result = graphService.buildShieldAnalysis(
                from.trim().toUpperCase(),
                to.trim().toUpperCase()
        );

        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/shield/certificate")
    public ResponseEntity<DelayCertificate> getDelayCertificate(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String passengerName,
            @RequestParam(required = false) String organization) {

        DelayCertificate certificate = graphService.buildDelayCertificate(
                from.trim().toUpperCase(),
                to.trim().toUpperCase(),
                passengerName,
                organization
        );

        if (certificate == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(certificate);
    }
}