# Shared by the aws-*.sh scripts. Admin credentials come from `aws login --profile <name>`.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

CDK_CLI_VERSION=2.1146.1
export AWS_PROFILE="${AWS_PROFILE:-safeledger-admin}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export CDK_DEFAULT_REGION="$AWS_REGION"
AWS="${AWS_CLI:-aws}"
command -v "$AWS" >/dev/null || AWS="/c/Program Files/Amazon/AWSCLIV2/aws.exe"
CDK_DEFAULT_ACCOUNT="$("$AWS" sts get-caller-identity --query Account --output text | tr -d '\r')"
export CDK_DEFAULT_ACCOUNT

cdk() {
  (cd infra/cdk && npx --yes "aws-cdk@$CDK_CLI_VERSION" "$@")
}
