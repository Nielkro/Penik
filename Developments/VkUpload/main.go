package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
)

type VKResponse[T any] struct {
	Response T `json:"response"`
	Error    *struct {
		ErrorCode int    `json:"error_code"`
		ErrorMsg  string `json:"error_msg"`
	} `json:"error"`
}

type UploadServerInfo struct {
	UploadURL string `json:"upload_url"`
}

type SaveDocItem struct {
	ID    int    `json:"id"`
	URL   string `json:"url"`
	Title string `json:"title"`
}

// -----------------------------------------------------------------------
// 1. Шифрование файла фото (AES-256-GCM)
// -----------------------------------------------------------------------
func encryptFile(fileBytes []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	return gcm.Seal(nonce, nonce, fileBytes, nil), nil
}

// -----------------------------------------------------------------------
// 2. Запрос upload_url через docs.getWallUploadServer (Без peer_id)
// -----------------------------------------------------------------------
func GetUploadURL(botToken string) (string, error) {
	// 1. Узнаем ID нашего сообщества через groups.getById
	groupAPI := fmt.Sprintf("https://api.vk.com/method/groups.getById?access_token=%s&v=5.131", url.QueryEscape(botToken))
	respGroup, err := http.Get(groupAPI)
	if err != nil {
		return "", fmt.Errorf("ошибка запроса группы: %w", err)
	}
	defer respGroup.Body.Close()

	var groupResp VKResponse[[]struct {
		ID int `json:"id"`
	}]
	json.NewDecoder(respGroup.Body).Decode(&groupResp)

	if groupResp.Error != nil || len(groupResp.Response) == 0 {
		return "", fmt.Errorf("ошибка получения ID группы: %v", groupResp.Error)
	}

	groupID := groupResp.Response[0].ID

	// 2. Запрашиваем upload_url для документов стены/сообщества
	apiURL := fmt.Sprintf(
		"https://api.vk.com/method/docs.getWallUploadServer?group_id=%d&access_token=%s&v=5.131",
		groupID,
		url.QueryEscape(botToken),
	)

	resp, err := http.Get(apiURL)
	if err != nil {
		return "", fmt.Errorf("ошибка запроса к VK API: %w", err)
	}
	defer resp.Body.Close()

	var vkResp VKResponse[UploadServerInfo]
	if err := json.NewDecoder(resp.Body).Decode(&vkResp); err != nil {
		return "", fmt.Errorf("ошибка декодирования ответа: %w", err)
	}

	if vkResp.Error != nil {
		return "", fmt.Errorf("VK API Error (%d): %s", vkResp.Error.ErrorCode, vkResp.Error.ErrorMsg)
	}

	return vkResp.Response.UploadURL, nil
}

// -----------------------------------------------------------------------
// 3. Загрузка и фиксация
// -----------------------------------------------------------------------
func EncryptAndUploadPhoto(photoPath string, secretKey []byte, uploadURL string, botToken string) (string, error) {
	photoBytes, err := os.ReadFile(photoPath)
	if err != nil {
		return "", fmt.Errorf("ошибка чтения файла %s: %w", photoPath, err)
	}

	encryptedData, err := encryptFile(photoBytes, secretKey)
	if err != nil {
		return "", fmt.Errorf("ошибка шифрования: %w", err)
	}

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	fileWriter, err := writer.CreateFormFile("file", "photo.bin")
	if err != nil {
		return "", err
	}
	fileWriter.Write(encryptedData)
	writer.Close()

	req, err := http.NewRequest("POST", uploadURL, body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	client := &http.Client{}
	uploadResp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer uploadResp.Body.Close()

	uploadResponseBody, _ := io.ReadAll(uploadResp.Body)
	var uploadResult map[string]interface{}
	json.Unmarshal(uploadResponseBody, &uploadResult)

	fileStr, _ := uploadResult["file"].(string)
	if fileStr == "" {
		return "", fmt.Errorf("VK не принял файл: %s", string(uploadResponseBody))
	}

	// Фиксируем зашифрованный документ
	saveAPIURL := fmt.Sprintf(
		"https://api.vk.com/method/docs.save?file=%s&title=encrypted_photo.bin&access_token=%s&v=5.131",
		url.QueryEscape(fileStr),
				  url.QueryEscape(botToken),
	)

	saveResp, err := http.Get(saveAPIURL)
	if err != nil {
		return "", err
	}
	defer saveResp.Body.Close()

	// VK может отдать массив документов или одиночный объект
	var saveResult map[string]interface{}
	json.NewDecoder(saveResp.Body).Decode(&saveResult)

	if errVal, ok := saveResult["error"]; ok {
		return "", fmt.Errorf("VK docs.save Error: %v", errVal)
	}

	respMap, _ := saveResult["response"].(map[string]interface{})
	if respMap != nil {
		// Разбираем гибкие варианты структурирования VK
		if docMap, ok := respMap["doc"].(map[string]interface{}); ok {
			return docMap["url"].(string), nil
		}
	}

	// Если VK вернул массив в response
	if respArr, ok := saveResult["response"].([]interface{}); ok && len(respArr) > 0 {
		if docMap, ok := respArr[0].(map[string]interface{}); ok {
			return docMap["url"].(string), nil
		}
	}

	return "", fmt.Errorf("не удалось извлечь URL из ответа docs.save: %s", string(uploadResponseBody))
}

func main() {
	botToken := os.Getenv("VK_BOT_TOKEN")
	if botToken == "" {
		fmt.Println("VK_BOT_TOKEN env var is not set")
		return
	}
	secretKey := []byte("12345678901234567890123456789012") // 32 байта AES-256
	photoPath := "zip.zip"

	fmt.Println("1. Запрашиваем upload_url через docs.getWallUploadServer...")
	uploadURL, err := GetUploadURL(botToken)
	if err != nil {
		fmt.Printf("Ошибка: %v\n", err)
		return
	}
	fmt.Printf("Успешно! Получен upload_url: %s\n\n", uploadURL)

	fmt.Printf("2. Шифруем фото (%s) и загружаем на VK CDN...\n", photoPath)
	cdnURL, err := EncryptAndUploadPhoto(photoPath, secretKey, uploadURL, botToken)
	if err != nil {
		fmt.Printf("Ошибка загрузки: %v\n", err)
		return
	}

	fmt.Println("\nУСПЕШНО ЗАГРУЖЕНО!")
	fmt.Printf("Прямая ссылка на зашифрованное фото (CDN VK):\n%s\n", cdnURL)
}
