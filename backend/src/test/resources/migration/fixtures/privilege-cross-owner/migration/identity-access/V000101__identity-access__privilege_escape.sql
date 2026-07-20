create table identity_access.ia_owned (id bigint primary key);
grant select on reporting.rp_report to scholarsense_identity_online;
grant select on identity_access.ia_owned to scholarsense_reporting_online;
grant select on identity_access.ia_owned, reporting.rp_report to scholarsense_identity_online;
grant select on all tables in schema identity_access, reporting to scholarsense_identity_online;
