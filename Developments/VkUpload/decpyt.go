package main

import (
	"crypto/aes"
	"crypto/cipher"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

func decryptFile(encryptedData []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonceSize := gcm.NonceSize()
	if len(encryptedData) < nonceSize {
		return nil, fmt.Errorf("данные слишком короткие")
	}

	// Вырезаем Nonce (первые 12 байт) и расшифровываем оставшийся шифротекст
	nonce, ciphertext := encryptedData[:nonceSize], encryptedData[nonceSize:]
	return gcm.Open(nil, nonce, ciphertext, nil)
}

func main() {
	// Вставляем ссылку, которую выдал main.go
	cdnURL := "https://vk.com/doc-215471784_707034296?hash=Oc6AzxvNi2tAxvRH4nRe5vExfk1ZF1g5lNLk1yZSj7T&dl=AKJIdkY1PwZ629eHJrNsTHLm9lmLorwSXJQFMCZ7RUP&api=1&no_preview=1"
	secretKey := []byte("12345678901234567890123456789012") // 32 байта (тот же ключ)

	fmt.Println("1. Скачиваем зашифрованный .bin файл с CDN VK...")
	req, err := http.NewRequest("GET", strings.TrimSpace(cdnURL), nil)
	if err != nil {
		fmt.Printf("Ошибка формирования запроса: %v\n", err)
		return
	}

	// Эмулируем User-Agent, чтобы VK не отдавал 403/400
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil || resp.StatusCode != 200 {
		fmt.Printf("Ошибка скачивания: status=%d, err=%v\n", resp.StatusCode, err)
		return
	}
	defer resp.Body.Close()

	encryptedBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		fmt.Printf("Ошибка чтения ответа: %v\n", err)
		return
	}
	fmt.Printf("Успешно скачано зашифрованных байт: %d\n", len(encryptedBytes))

	fmt.Println("2. Расшифровываем байты фотографии...")
	originalPhotoBytes, err := decryptFile(encryptedBytes, secretKey)
	if err != nil {
		fmt.Printf("❌ Ошибка AES-GCM расшифровки: %v\n", err)
		return
	}

	// Сохраняем расшифрованный файл на диск
	outPath := "restored_photo.jpg"
	err = os.WriteFile(outPath, originalPhotoBytes, 0644)
	if err != nil {
		fmt.Printf("Ошибка сохранения файла: %v\n", err)
		return
	}

	fmt.Printf("\n🎉 УСПЕШНО! Восстановленная фотография сохранена в '%s'. Открой её на ПК!\n", outPath)
}
