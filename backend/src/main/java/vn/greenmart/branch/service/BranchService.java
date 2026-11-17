package vn.greenmart.branch.service;

import vn.greenmart.branch.dto.BranchResponse;
import vn.greenmart.branch.dto.BranchSaleInfo;

import java.util.List;

public interface BranchService {
    BranchSaleInfo getSaleInfo(Long branchId);

    List<BranchResponse> list(Boolean active);
}
