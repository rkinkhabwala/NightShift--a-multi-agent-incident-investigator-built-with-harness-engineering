package dev.nightshift.common.admin;

import dev.nightshift.common.switches.FaultSwitches;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Chaos-controller-only endpoint for the hidden fault switches. Not an agent tool. */
@RestController
@RequestMapping("/internal/switches")
public class InternalSwitchesController {

    private final FaultSwitches switches;

    public InternalSwitchesController(FaultSwitches switches) {
        this.switches = switches;
    }

    @GetMapping
    public Map<String, String> get() {
        return switches.snapshot();
    }

    @PutMapping
    public Map<String, String> replace(@RequestBody Map<String, String> next) {
        switches.replace(next);
        return switches.snapshot();
    }
}
