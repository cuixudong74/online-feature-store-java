package com.github.cuixudong74.onlinefeaturestorejava.api;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureVector;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/features")
public class FeatureController {
    private final FeatureService service;

    public FeatureController(FeatureService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public FeatureDefinition register(@RequestBody FeatureDefinition definition) {
        return service.registerFeature(definition);
    }

    @PostMapping("/deprecate")
    public void deprecate(@RequestParam String name, @RequestParam int version,
                          @RequestParam Instant when) {
        service.deprecateFeature(name, version, when);
    }

    @PostMapping("/write")
    public void write(@RequestBody List<FeatureValue> values) {
        service.writeFeatures(values);
    }

    @PostMapping("/read")
    public FeatureVector read(@RequestBody ReadRequest request) {
        return service.getFeatures(request.entityId(), request.features(), request.asOf());
    }

    @GetMapping("/read")
    public FeatureVector readLatest(@RequestParam String entityId,
                                    @RequestParam List<String> features,
                                    @RequestParam Instant asOf) {
        List<FeatureService.FeatureRequest> reqs = features.stream()
                .map(f -> new FeatureService.FeatureRequest(f, null))
                .toList();
        return service.getFeatures(entityId, reqs, asOf);
    }

    public record ReadRequest(String entityId, List<FeatureService.FeatureRequest> features, Instant asOf) {}
}
