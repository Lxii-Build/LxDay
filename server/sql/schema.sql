-- ============================================================
-- 林曦日记 数据库初始化脚本
-- MySQL 8.0 / utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS `linxi` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `linxi`;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `nickname`      VARCHAR(32)  NOT NULL,
  `avatar_url`    VARCHAR(255) DEFAULT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_nickname` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 双人绑定关系（核心数据隔离键：pair_id）
CREATE TABLE IF NOT EXISTS `pair` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_a_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '空位为 0，等待对方加入',
  `user_b_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `invite_code`  VARCHAR(8)   NOT NULL,
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1已绑定 0已解绑',
  `unbind_time`  DATETIME     DEFAULT NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invite_code` (`invite_code`),
  KEY `idx_user_a` (`user_a_id`),
  KEY `idx_user_b` (`user_b_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双人绑定';

-- 最新设备状态（每人一行，读写频繁，常驻内存/Redis，落库兜底）
CREATE TABLE IF NOT EXISTS `device_status` (
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `battery_level`   INT          NOT NULL DEFAULT 0,
  `is_charging`     TINYINT      NOT NULL DEFAULT 0,
  `screen_on`       TINYINT      NOT NULL DEFAULT 0,
  `is_locked`       TINYINT      NOT NULL DEFAULT 0,
  `foreground_pkg`  VARCHAR(128) DEFAULT NULL,
  `foreground_name` VARCHAR(64)  DEFAULT NULL,
  `music_title`     VARCHAR(128) DEFAULT NULL,
  `music_artist`    VARCHAR(64)  DEFAULT NULL,
  `is_playing`      TINYINT      NOT NULL DEFAULT 0,
  `ssid`            VARCHAR(64)  DEFAULT NULL,
  `network_type`    VARCHAR(16)  NOT NULL DEFAULT 'wifi',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备状态(最新)';

-- 当日应用使用时长
CREATE TABLE IF NOT EXISTS `app_usage_daily` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `app_pkg`       VARCHAR(128) NOT NULL,
  `app_name`      VARCHAR(64)  NOT NULL,
  `usage_minutes` INT          NOT NULL DEFAULT 0,
  `stat_date`     DATE         NOT NULL,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date_app` (`user_id`,`stat_date`,`app_pkg`),
  KEY `idx_user_date` (`user_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用使用时长(按日)';

-- 双向待办
CREATE TABLE IF NOT EXISTS `todo` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pair_id`      BIGINT UNSIGNED NOT NULL,
  `creator_id`   BIGINT UNSIGNED NOT NULL,
  `assignee_id`  BIGINT UNSIGNED NOT NULL,
  `title`        VARCHAR(128) NOT NULL,
  `note`         VARCHAR(500) DEFAULT NULL,
  `remind_at`    DATETIME     DEFAULT NULL,
  `remind_type`  TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通 1强提醒',
  `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待办 1已完成 2已删除',
  `completed_at` DATETIME     DEFAULT NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pair_status` (`pair_id`,`status`),
  KEY `idx_assignee_remind` (`assignee_id`,`remind_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待办';

-- 状态历史（5 分钟聚合，永久保留）
CREATE TABLE IF NOT EXISTS `status_history` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pair_id`         BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `battery`         INT          NOT NULL DEFAULT 0,
  `charging`        TINYINT      NOT NULL DEFAULT 0,
  `screen_on`       TINYINT      NOT NULL DEFAULT 0,
  `locked`          TINYINT      NOT NULL DEFAULT 1,
  `foreground_pkg`  VARCHAR(128) DEFAULT NULL,
  `foreground_name` VARCHAR(64)  DEFAULT NULL,
  `ssid`            VARCHAR(64)  DEFAULT NULL,
  `network`         VARCHAR(16)  NOT NULL DEFAULT 'wifi',
  `ts`              DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_user_ts` (`pair_id`,`user_id`,`ts`),
  KEY `idx_pair_user_ts_desc` (`pair_id`,`user_id`,`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态历史';

-- 双人共同日记
CREATE TABLE IF NOT EXISTS `diary` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pair_id`     BIGINT UNSIGNED NOT NULL,
  `author_id`   BIGINT UNSIGNED NOT NULL,
  `title`       VARCHAR(128) NOT NULL,
  `content`     TEXT         NOT NULL,
  `diary_date`  DATE         NOT NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pair_date` (`pair_id`,`diary_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日记';

CREATE TABLE IF NOT EXISTS `diary_image` (
  `id`       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `diary_id` BIGINT UNSIGNED NOT NULL,
  `url`      VARCHAR(500) NOT NULL,
  `sort_no`  INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_diary` (`diary_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日记图片';

-- 推送令牌
CREATE TABLE IF NOT EXISTS `push_token` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `platform`   VARCHAR(16) NOT NULL,
  `channel`    VARCHAR(16) NOT NULL COMMENT 'getui/jpush',
  `token`      VARCHAR(255) NOT NULL,
  `status`     TINYINT      NOT NULL DEFAULT 1,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_channel` (`user_id`,`channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送令牌';
