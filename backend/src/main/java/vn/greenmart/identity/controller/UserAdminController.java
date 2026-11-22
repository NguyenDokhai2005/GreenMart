package vn.greenmart.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.identity.dto.UserRequests;
import vn.greenmart.identity.dto.UserResponse;
import vn.greenmart.identity.service.UserAdminService;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserAdminController {
    private final UserAdminService service;
    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required=false) UserRequests.Role role,
            @RequestParam(required=false) Long branchId, @RequestParam(required=false) Boolean active,
            @RequestParam(required=false) String q, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="id,asc") String sort) {
        return service.list(role,branchId,active,q,page,size,sort);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody UserRequests.Create request) { return service.create(request); }
    @GetMapping("/{userId}") public UserResponse get(@PathVariable long userId) { return service.get(userId); }
    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable long userId, @Valid @RequestBody UserRequests.Update request) {
        return service.update(userId,request);
    }
    @PostMapping("/{userId}/unlock") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable long userId) { service.unlock(userId); }
    @PostMapping("/{userId}/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable long userId, @Valid @RequestBody UserRequests.ResetPassword request) {
        service.resetPassword(userId,request.newPassword());
    }
}
