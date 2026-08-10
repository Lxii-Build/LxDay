package main

import (
	"database/sql"
	"strconv"

	"github.com/gin-gonic/gin"
)

// ================= APP 版本发布 & 检查更新 =================

func scanAppVersion(row interface{ Scan(...interface{}) error }) (*AppVersion, error) {
	v := &AppVersion{}
	var apk, notes sql.NullString
	if err := row.Scan(&v.ID, &v.Platform, &v.VersionName, &v.VersionCode, &apk, &notes, &v.ForceUpdate, &v.Status, &v.CreatedAt); err != nil {
		return nil, err
	}
	v.APKURL = apk.String
	v.Notes = notes.String
	return v, nil
}

const appVersionCols = `id,platform,version_name,version_code,apk_url,notes,force_update,status,created_at`

func (s *Store) LatestAppVersion(platform string) (*AppVersion, error) {
	return scanAppVersion(s.DB.QueryRow(
		`SELECT `+appVersionCols+` FROM app_version WHERE platform=? AND status=1 ORDER BY version_code DESC LIMIT 1`, platform))
}

func (s *Store) ListAppVersions(platform string, limit, offset int) ([]AppVersion, int, error) {
	where := ""
	args := []interface{}{}
	if platform != "" {
		where = " WHERE platform=?"
		args = append(args, platform)
	}
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM app_version"+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(`SELECT `+appVersionCols+` FROM app_version`+where+` ORDER BY id DESC LIMIT ? OFFSET ?`,
		append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []AppVersion{}
	for rows.Next() {
		v, err := scanAppVersion(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *v)
	}
	return out, total, nil
}

func (s *Store) CreateAppVersion(v *AppVersion) (int64, error) {
	res, err := s.DB.Exec(
		`INSERT INTO app_version(platform,version_name,version_code,apk_url,notes,force_update,status) VALUES(?,?,?,?,?,?,?)`,
		v.Platform, v.VersionName, v.VersionCode, v.APKURL, v.Notes, v.ForceUpdate, v.Status)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) SetAppVersionStatus(id int64, status int) error {
	_, err := s.DB.Exec(`UPDATE app_version SET status=? WHERE id=?`, status, id)
	return err
}

func (s *Store) DeleteAppVersion(id int64) error {
	_, err := s.DB.Exec(`DELETE FROM app_version WHERE id=?`, id)
	return err
}

// handleCheckUpdate 客户端检查更新（公开接口）
func handleCheckUpdate(c *gin.Context) {
	platform := c.DefaultQuery("platform", "android")
	cur, _ := strconv.Atoi(c.DefaultQuery("version_code", "0"))
	v, err := st.LatestAppVersion(platform)
	if err != nil {
		ok(c, gin.H{"has_update": false})
		return
	}
	has := v.VersionCode > cur
	ok(c, gin.H{"has_update": has, "force": has && v.ForceUpdate, "version": v})
}
