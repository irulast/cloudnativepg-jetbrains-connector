# CloudNativePG Connector for JetBrains IDEs

[![JetBrains Plugin Version](https://img.shields.io/jetbrains/plugin/v/30025-cloudnativepg-connector.svg)](https://plugins.jetbrains.com/plugin/30025-cloudnativepg-connector)
[![JetBrains Plugin Downloads](https://img.shields.io/jetbrains/plugin/d/30025-cloudnativepg-connector.svg)](https://plugins.jetbrains.com/plugin/30025-cloudnativepg-connector)
[![JetBrains Plugin Rating](https://img.shields.io/jetbrains/plugin/r/rating/30025-cloudnativepg-connector.svg)](https://plugins.jetbrains.com/plugin/30025-cloudnativepg-connector)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Connect to [CloudNativePG](https://cloudnative-pg.io/) PostgreSQL clusters running in Kubernetes directly from your JetBrains IDE. Discover clusters, import them as data sources, and start querying — credentials and port-forwarding are handled automatically.

**Supported IDEs:** IntelliJ IDEA Ultimate, DataGrip, PyCharm Professional, GoLand, Rider, WebStorm, and other JetBrains IDEs with Database Tools support.

**Requires:** IntelliJ Platform 2026.1 or later.

## Features

- **Cloud Import** — Discover CloudNativePG clusters via Database → Import from Cloud
- **Automatic Port-Forwarding** — kubectl port-forwards are established on demand when you connect
- **Live Credentials** — Credentials are fetched fresh from Kubernetes secrets on every connection — nothing is stored locally
- **Zero Configuration** — No passwords to manage or sync; the auth provider handles everything
- **Multi-Context Support** — Switch between Kubernetes contexts to access clusters in different environments
- **Primary & Replica** — Connect to the primary instance or read-only replicas
- **Auto-Reconnect** — Port-forwards are re-established automatically when the IDE reconnects

## Installation

### From JetBrains Marketplace

1. Open your JetBrains IDE (IntelliJ IDEA Ultimate, DataGrip, PyCharm Professional, etc.)
2. Go to **Settings/Preferences** → **Plugins** → **Marketplace**
3. Search for "CloudNativePG Connector"
4. Click **Install** and restart your IDE

### Manual Installation

1. Download the latest release from the [Releases](https://github.com/irulast/cloudnativepg-jetbrains-connector/releases) page
2. Go to **Settings/Preferences** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the downloaded `.zip` file and restart your IDE

## Requirements

- **JetBrains IDE** with Database Tools support (IntelliJ IDEA Ultimate, DataGrip, PyCharm Professional, GoLand, etc.)
- **kubectl** configured with access to your Kubernetes cluster(s)
- **CloudNativePG** operator installed in your Kubernetes cluster
- **RBAC permissions** to:
  - List and get CloudNativePG Cluster resources
  - Read Secrets (for database credentials)
  - Create port-forwards to Pods

## Quick Start

1. Open the **Database** tool window
2. Click **+** → **Import from Cloud**
3. Select **CloudNativePG (Kubernetes)**
4. Choose your Kubernetes context and optionally filter by namespace
5. Import the clusters you want
6. Click **Connect** on any imported data source — port-forwarding and credentials are handled automatically

## How It Works

When you connect to an imported CloudNativePG data source, the plugin's auth provider:

1. Reads the Kubernetes context, namespace, and cluster metadata from the data source
2. Establishes a kubectl port-forward to the appropriate pod (primary or replica)
3. Fetches fresh credentials from the cluster's Kubernetes secret
4. Injects the connection URL and credentials into the JDBC connection

All of this happens transparently — you just click Connect and start querying.

### Auto-Reconnect

When you restart your IDE, imported data sources are preserved. On reconnect, the auth provider re-establishes the port-forward with a fresh port and fetches the latest credentials from Kubernetes. No manual intervention needed.

### Replicas

To connect to a read-only replica, edit the data source properties and change the `cnpg.replica` additional property to `true`.

## Configuration

Go to **Settings/Preferences** → **Tools** → **CloudNativePG Connector**:

| Setting | Description | Default |
|---------|-------------|---------|
| Local port range | Port range for port-forwarding | 15432–15532 |
| Show notifications | Display connection status notifications | Enabled |
| Data source naming pattern | Pattern for naming imported data sources | `${namespace}/${name}` |
| Use folder organization | Group data sources by context/namespace | Enabled |
| Mark replica as read-only | Flag replica connections as read-only | Enabled |

## Troubleshooting

### Cluster Not Appearing in Import

- Verify kubectl can access the cluster: `kubectl get clusters.postgresql.cnpg.io --all-namespaces`
- Check that the CloudNativePG CRDs are installed
- Ensure your kubeconfig context has the necessary RBAC permissions
- Try a different namespace filter or leave it blank to search all namespaces

### Port-Forward Issues

- Check if another process is using ports in the configured range
- Verify kubectl port-forward works manually: `kubectl port-forward svc/<cluster>-rw 5432:5432 -n <namespace>`
- The plugin will retry failed port-forwards automatically (up to 3 attempts)

### Authentication Errors

Credentials are fetched live from the Kubernetes secret `<cluster>-app` on every connection attempt. If you see authentication errors:

- Verify the secret exists: `kubectl get secret <cluster>-app -n <namespace>`
- Check that the secret contains `dbname`, `username`/`user`, and `password` keys
- Ensure your RBAC permissions allow reading secrets in the cluster's namespace

## Building from Source

```bash
# Clone the repository
git clone https://github.com/irulast/cloudnativepg-jetbrains-connector.git
cd cloudnativepg-jetbrains-connector

# Build the plugin
./gradlew build

# Run in a development IDE instance
./gradlew runIde

# Build distribution zip
./gradlew buildPlugin
```

The built plugin will be in `build/distributions/`.

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## About

Created and maintained by [Irulast](https://irulast.com) — *Your Application, In Production*.

This plugin is not officially affiliated with the CloudNativePG project, but is built to work seamlessly with CloudNativePG clusters.

## Acknowledgments

- [CloudNativePG](https://cloudnative-pg.io/) — The Kubernetes operator for PostgreSQL
- [JetBrains](https://www.jetbrains.com/) — For the IDE platform and Database Tools APIs
- [Fabric8](https://fabric8.io/) — For the Kubernetes Java client
