alter table plant add column registered_at text;

create table dormancy_period (
    id text not null primary key,
    plant_id text not null references plant(id),
    started_at text not null,
    ended_at text
) strict;

create index dormancy_period_plant_started_idx on dormancy_period (plant_id, started_at);

alter table operation add column is_watering integer
    check (is_watering in (0, 1));

create index operation_watering_plant_date_id_idx
    on operation (plant_id, date desc, id desc) where is_watering = 1;
