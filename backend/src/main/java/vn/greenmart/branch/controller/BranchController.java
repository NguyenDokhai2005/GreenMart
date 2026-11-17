package vn.greenmart.branch.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.branch.dto.BranchResponse;
import vn.greenmart.branch.service.BranchService;

import java.util.List;

@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
public class BranchController {
    private final BranchService service;

    @GetMapping
    public List<BranchResponse> list(@RequestParam(name = "active", required = false) Boolean active) {
        return service.list(active);
    }
}
