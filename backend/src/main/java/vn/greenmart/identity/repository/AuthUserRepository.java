package vn.greenmart.identity.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.identity.dto.CurrentUser;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AuthUserRepository {
    private final JdbcTemplate jdbc;
    public record User(long id, String username, String passwordHash, String fullName, String role,
                       boolean active, int failures, OffsetDateTime lockedUntil) {}

    private User map(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"),rs.getString("username"),rs.getString("password_hash"),
                rs.getString("full_name"),rs.getString("role"),rs.getBoolean("is_active"),
                rs.getInt("failed_login_count"),rs.getObject("locked_until",OffsetDateTime.class));
    }
    public Optional<User> lock(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username=? FOR UPDATE",this::map,username).stream().findFirst();
    }
    public Optional<User> find(long id, boolean lock) {
        return jdbc.query("SELECT * FROM app_user WHERE id=?"+(lock?" FOR UPDATE":""),this::map,id).stream().findFirst();
    }
    public Optional<String> activeRole(long id) {
        return jdbc.queryForList("SELECT role FROM app_user WHERE id=? AND is_active",String.class,id)
                .stream().findFirst();
    }
    public void loginState(long id, int failures, OffsetDateTime lockedUntil) {
        jdbc.update("UPDATE app_user SET failed_login_count=?,locked_until=? WHERE id=?",failures,lockedUntil,id);
    }
    public void password(long id, String hash) { jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",hash,id); }
    public CurrentUser current(User user) {
        List<CurrentUser.Branch> branches = jdbc.query("""
                SELECT b.id,b.code,b.name FROM branch b
                WHERE ((? IN ('ADMIN','OWNER') AND b.is_active)
                  OR (? NOT IN ('ADMIN','OWNER') AND EXISTS
                    (SELECT 1 FROM user_branch ub WHERE ub.user_id=? AND ub.branch_id=b.id)))
                ORDER BY b.id
                """,(rs,n)->new CurrentUser.Branch(rs.getLong("id"),rs.getString("code"),rs.getString("name")),
                user.role(),user.role(),user.id());
        return new CurrentUser(user.id(),user.username(),user.fullName(),user.role(),branches);
    }
}
