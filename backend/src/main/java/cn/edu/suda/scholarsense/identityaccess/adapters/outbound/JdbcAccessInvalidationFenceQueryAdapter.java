package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationFenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the business fence applied by authorization-current-scope. */
public final class JdbcAccessInvalidationFenceQueryAdapter
        implements AccessInvalidationFenceQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcAccessInvalidationFenceQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean blocks(AccessInvalidationLineageId lineageId) {
        Boolean blocked = jdbc.queryForObject("""
                select exists (
                  select 1
                    from identity_access
                      .ia_access_invalidation_local_fence
                   where consumer_id='authorization-current-scope'
                     and aggregate_type='responsibility-scope'
                     and lineage_id=?
                     and current_state='invalidated'
                )
                """, Boolean.class, lineageId.value());
        return Boolean.TRUE.equals(blocked);
    }
}
