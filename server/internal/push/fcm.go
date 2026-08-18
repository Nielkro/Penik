package push

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"messenger/server/internal/db"
)

type ServiceAccount struct {
	Type                    string `json:"type"`
	ProjectID               string `json:"project_id"`
	PrivateKeyID            string `json:"private_key_id"`
	PrivateKey              string `json:"private_key"`
	ClientEmail             string `json:"client_email"`
	ClientID                string `json:"client_id"`
	AuthURI                 string `json:"auth_uri"`
	TokenURI                string `json:"token_uri"`
	AuthProviderX509CertURL string `json:"auth_provider_x509_cert_url"`
	ClientX509CertURL       string `json:"client_x509_cert_url"`
}

var (
	fcmClient *FCMClient
	once      sync.Once
)

type FCMClient struct {
	sa          ServiceAccount
	token       string
	expiry      time.Time
	mu          sync.Mutex
	privateKey  *rsa.PrivateKey
}

func GetFCMClient() *FCMClient {
	once.Do(func() {
		saPath := "firebase-service-account.json"
		if _, err := os.Stat(saPath); os.IsNotExist(err) {
			saPath = "server/firebase-service-account.json"
		}
		if _, err := os.Stat(saPath); os.IsNotExist(err) {
			saPath = "../firebase-service-account.json"
		}

		data, err := os.ReadFile(saPath)
		if err != nil {
			log.Printf("push: firebase-service-account.json not found, push notifications disabled")
			return
		}

		var sa ServiceAccount
		if err := json.Unmarshal(data, &sa); err != nil {
			log.Printf("push: failed to parse firebase-service-account.json: %v", err)
			return
		}

		block, _ := pem.Decode([]byte(sa.PrivateKey))
		if block == nil {
			log.Printf("push: failed to decode private key PEM")
			return
		}

		privKey, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			privKey, err = x509.ParsePKCS1PrivateKey(block.Bytes)
			if err != nil {
				log.Printf("push: failed to parse private key: %v", err)
				return
			}
		}

		rsaKey, ok := privKey.(*rsa.PrivateKey)
		if !ok {
			log.Printf("push: private key is not an RSA key")
			return
		}

		fcmClient = &FCMClient{
			sa:         sa,
			privateKey: rsaKey,
		}
		log.Printf("push: FCM Client initialized successfully for project %s", sa.ProjectID)
	})
	return fcmClient
}

func (c *FCMClient) getAccessToken() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.token != "" && time.Now().Before(c.expiry) {
		return c.token, nil
	}

	now := time.Now().Unix()
	claims := map[string]interface{}{
		"iss":   c.sa.ClientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   c.sa.TokenURI,
		"exp":   now + 3600,
		"iat":   now,
	}

	headerJSON, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT"})
	claimsJSON, _ := json.Marshal(claims)

	tokenStr, err := c.signJWT(headerJSON, claimsJSON)
	if err != nil {
		return "", err
	}

	resp, err := http.Post(c.sa.TokenURI, "application/x-www-form-urlencoded",
		bytes.NewBufferString(fmt.Sprintf("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=%s", tokenStr)))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("token exchange returned status %d: %s", resp.StatusCode, string(body))
	}

	var tokenResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tokenResp); err != nil {
		return "", err
	}

	c.token = tokenResp.AccessToken
	c.expiry = time.Now().Add(time.Duration(tokenResp.ExpiresIn-60) * time.Second)
	return c.token, nil
}

func (c *FCMClient) signJWT(header, claims []byte) (string, error) {
	encHeader := base64.RawURLEncoding.EncodeToString(header)
	encClaims := base64.RawURLEncoding.EncodeToString(claims)
	input := encHeader + "." + encClaims

	h := sha256.New()
	h.Write([]byte(input))
	hashed := h.Sum(nil)

	sig, err := rsa.SignPKCS1v15(rand.Reader, c.privateKey, crypto.SHA256, hashed)
	if err != nil {
		return "", err
	}

	return input + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

// SendDirectMessagePush sends background notification to all user's devices except the sender device.
func SendDirectMessagePush(database *db.DB, recipientUserID, senderUserID int64, senderName, text string) {
	client := GetFCMClient()
	if client == nil {
		return
	}

	rows, err := database.Query("SELECT fcm_token FROM devices WHERE user_id = ? AND fcm_token != ''", recipientUserID)
	if err != nil {
		return
	}
	defer rows.Close()

	var tokens []string
	for rows.Next() {
		var token string
		if err := rows.Scan(&token); err == nil {
			tokens = append(tokens, token)
		}
	}

	log.Printf("push: sending direct message push to user %d (found %d tokens)", recipientUserID, len(tokens))

	for _, token := range tokens {
		go client.sendPushPayload(token, map[string]string{
			"type":           "direct",
			"chat_user_id":   fmt.Sprintf("%d", senderUserID),
			"sender_name":    senderName,
			"text":           text,
			"timestamp":      fmt.Sprintf("%d", time.Now().UnixMilli()),
			"sender_user_id": fmt.Sprintf("%d", senderUserID),
		})
	}
}

// SendGroupMessagePush sends background notification to all group members' devices except the sender.
func SendGroupMessagePush(database *db.DB, groupID int64, senderUserID int64, senderName, groupName, text string) {
	client := GetFCMClient()
	if client == nil {
		return
	}

	rows, err := database.Query(`
		SELECT d.fcm_token 
		  FROM group_members m
		  JOIN devices d ON d.user_id = m.user_id
		 WHERE m.group_id = ? AND m.user_id != ? AND d.fcm_token != ''`,
		groupID, senderUserID)
	if err != nil {
		return
	}
	defer rows.Close()

	var tokens []string
	for rows.Next() {
		var token string
		if err := rows.Scan(&token); err == nil {
			tokens = append(tokens, token)
		}
	}

	log.Printf("push: sending group message push to group %d (found %d tokens)", groupID, len(tokens))

	for _, token := range tokens {
		go client.sendPushPayload(token, map[string]string{
			"type":           "group",
			"group_id":       fmt.Sprintf("%d", groupID),
			"group_name":     groupName,
			"sender_user_id": fmt.Sprintf("%d", senderUserID),
			"sender_name":    senderName,
			"text":           text,
			"timestamp":      fmt.Sprintf("%d", time.Now().UnixMilli()),
		})
	}
}

func (c *FCMClient) sendPushPayload(token string, data map[string]string) {
	accessToken, err := c.getAccessToken()
	if err != nil {
		log.Printf("push: failed to get access token: %v", err)
		return
	}

	payload := map[string]interface{}{
		"message": map[string]interface{}{
			"token": token,
			"data":  data,
			"android": map[string]interface{}{
				"priority": "high",
			},
		},
	}

	bodyJSON, _ := json.Marshal(payload)
	url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", c.sa.ProjectID)

	req, err := http.NewRequest("POST", url, bytes.NewBuffer(bodyJSON))
	if err != nil {
		return
	}

	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		log.Printf("push: failed to send HTTP request: %v", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		log.Printf("push: successfully sent push notification to project %s", c.sa.ProjectID)
	} else {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("push: send failed with status %d: %s", resp.StatusCode, string(respBody))
	}
}
