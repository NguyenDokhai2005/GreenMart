package vn.greenmart.branch.service;

import vn.greenmart.branch.dto.BranchSaleInfo;

public interface BranchService {
    BranchSaleInfo getSaleInfo(Long branchId);
}
