package vn.greenmart.integration.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/integrations/sync-logs")
public class SyncLogController {
    private final SyncLogService logs;
    public SyncLogController(SyncLogService logs) { this.logs = logs; }
    @GetMapping
    public SyncLogService.Page list(@RequestParam(required=false) String channelCode,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return logs.list(channelCode, status, page, size);
    }
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable long id) { logs.retry(id); }
}
