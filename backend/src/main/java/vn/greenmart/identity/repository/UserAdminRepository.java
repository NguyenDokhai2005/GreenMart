package vn.greenmart.identity.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Repository
@RequiredArgsConstructor
public class UserAdminRepository {
    private final JdbcTemplate jdbc;
    private static final Map<String,String> SORT = Map.of("id","u.id", "username","u.username",
            "fullName","u.full_name", "role","u.role", "active","u.is_active");
    public record Results(List<Long> ids, long total) {}

    public Results search(String role, Long branchId, Boolean active, String query, int page, int size, String sort) {
        String[] parts=sort.split(",",-1);
        String column=SORT.get(parts[0]);
        if(column==null || parts.length>2 || (parts.length==2 && !List.of("asc","desc").contains(parts[1])))
            throw new AppException(ErrorCode.VALIDATION_ERROR,"Trường hoặc chiều sắp xếp không hợp lệ");
        String direction=parts.length==2?parts[1]:"asc";
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        List<Object> args=new ArrayList<>();
        if(role!=null) { where.append(" AND u.role=?"); args.add(role); }
        if(active!=null) { where.append(" AND u.is_active=?"); args.add(active); }
        if(branchId!=null) {
            where.append(" AND (u.role IN ('ADMIN','OWNER') AND EXISTS (SELECT 1 FROM branch b WHERE b.id=? AND b.is_active)"
                    + " OR EXISTS (SELECT 1 FROM user_branch ub WHERE ub.user_id=u.id AND ub.branch_id=?))");
            args.add(branchId); args.add(branchId);
        }
        if(query!=null && !query.isBlank()) {
            where.append(" AND (strpos(lower(u.username),?)>0 OR strpos(lower(u.full_name),?)>0)");
            String q=query.strip().toLowerCase(Locale.ROOT); args.add(q); args.add(q);
        }
        long total=jdbc.queryForObject("SELECT count(*) FROM app_user u"+where,Long.class,args.toArray());
        args.add(size); args.add((long)page*size);
        var ids=jdbc.queryForList("SELECT u.id FROM app_user u"+where+" ORDER BY "+column+" "+direction
                +",u.id ASC LIMIT ? OFFSET ?",Long.class,args.toArray());
        return new Results(ids,total);
    }
    public void lockAdministration() { jdbc.queryForList("SELECT pg_advisory_xact_lock(424)"); }
    public boolean usernameExists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE username=?)", Boolean.class, username));
    }
    public long create(String username, String fullName, String role, String hash) {
        return jdbc.queryForObject("INSERT INTO app_user(username,full_name,role,password_hash) VALUES(?,?,?,?) RETURNING id",
                Long.class,username,fullName,role,hash);
    }
    public void update(long id, String fullName, String role, boolean active) {
        jdbc.update("UPDATE app_user SET full_name=?,role=?,is_active=? WHERE id=?",fullName,role,active,id);
    }
    public List<Long> branches(long id) {
        return jdbc.queryForList("SELECT branch_id FROM user_branch WHERE user_id=? ORDER BY branch_id",Long.class,id);
    }
    public boolean lockBranches(List<Long> ids) {
        String parameters=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        return jdbc.queryForList("SELECT id FROM branch WHERE is_active AND id IN ("+parameters+") ORDER BY id FOR SHARE",
                Long.class,ids.toArray()).size()==ids.size();
    }
    public void replaceBranches(long id, List<Long> branches) {
        jdbc.update("DELETE FROM user_branch WHERE user_id=?",id);
        for(long branch:branches) jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(?,?)",id,branch);
    }
}
