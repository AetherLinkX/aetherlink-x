package all

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"

	"github.com/cloudflare/circl/kem/xwing"
	"github.com/xtls/xray-core/common/uuid"
	"github.com/xtls/xray-core/main/commands/base"
)

var cmdAetherLinkXKeygen = &base.Command{
	UsageLine: `{{.Exec}} aetherlinkx-keygen`,
	Short:     `Generate an AetherLink X account and X-Wing hybrid KEM key pair`,
	Long: `
Generate independent credentials for AetherLink X.

The account ID and secret are shared by one client and server user entry.
Put XWingPrivateKey only on the server and XWingPublicKey only on clients.
Never reuse the account secret as a REALITY, WireGuard, or X-Wing key.
`,
}

func init() {
	cmdAetherLinkXKeygen.Run = executeAetherLinkXKeygen
}

func executeAetherLinkXKeygen(_ *base.Command, _ []string) {
	var secret [32]byte
	if _, err := rand.Read(secret[:]); err != nil {
		base.Errorf("failed to generate AetherLink X account secret: %v", err)
		return
	}
	privateKey, publicKey, err := xwing.GenerateKeyPairPacked(nil)
	if err != nil {
		base.Errorf("failed to generate AetherLink X X-Wing key pair: %v", err)
		return
	}
	id := uuid.New()
	fmt.Printf("ID: %s\nSecret: %s\nXWingPrivateKey: %s\nXWingPublicKey: %s\n",
		id.String(),
		base64.RawURLEncoding.EncodeToString(secret[:]),
		base64.RawURLEncoding.EncodeToString(privateKey),
		base64.RawURLEncoding.EncodeToString(publicKey),
	)
}
