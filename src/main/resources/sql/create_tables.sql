CREATE TABLE cord_meeting (
    id BIGSERIAL PRIMARY KEY,
    meeting_id VARCHAR(255),
    start_time BIGINT,
    end_time BIGINT
);

CREATE TABLE cord_channel (
	id bigserial NOT NULL,
	"name" varchar(255) NULL,
	ban bool DEFAULT false NOT NULL,
	CONSTRAINT cord_channel_name_key UNIQUE (name),
	CONSTRAINT cord_channel_pkey PRIMARY KEY (id)
);

CREATE TABLE cord_user (
	id bigserial NOT NULL,
	telegram_id int8 NULL,
	username varchar(255) NULL,
	first_name varchar(255) NULL,
	last_name varchar(255) NULL,
	auth_date date NULL,
	allows_write_to_pm bool DEFAULT false NOT NULL,
	language_code varchar(50) NULL,
	is_premium bool DEFAULT false NOT NULL,
	CONSTRAINT cord_user_pkey PRIMARY KEY (id),
	CONSTRAINT cord_user_telegram_id_key UNIQUE (telegram_id),
	CONSTRAINT cord_user_username_key UNIQUE (username)
);


CREATE TABLE cord_message (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    send_time DATE,
    sender BIGINT,
    channel BIGINT,
    CONSTRAINT fk_message_sender
        FOREIGN KEY (sender) REFERENCES cord_user(id),
    CONSTRAINT fk_message_channel
        FOREIGN KEY (channel) REFERENCES cord_channel(id)
);
