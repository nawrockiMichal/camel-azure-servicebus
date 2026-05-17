package uk.selflearning.apache.camel.inspection;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/queues")
public class QueueInspectionController {

    private final QueueInspectionService inspectionService;

    public QueueInspectionController(QueueInspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    // ?view=camel adds Camel exchange header names and Camel-only fields alongside each Azure SB field
    @GetMapping("/{name}/messages")
    public List<MessageView> peekMessages(
            @PathVariable String name,
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(defaultValue = "default") String view) {
        return inspectionService.peekMessages(name, count, "camel".equals(view));
    }

    @GetMapping("/{name}/deadletter")
    public List<MessageView> peekDeadLetter(
            @PathVariable String name,
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(defaultValue = "default") String view) {
        return inspectionService.peekDeadLetterMessages(name, count, "camel".equals(view));
    }
}
