#!/usr/bin/env bash
# Deploys SafeLedger to AWS. Billed by the hour until scripts/aws-destroy.sh runs.
#   ALLOWED_CIDR=203.0.113.7/32 scripts/aws-deploy.sh
. "$(dirname "${BASH_SOURCE[0]}")/aws-common.sh"
: "${ALLOWED_CIDR:?set ALLOWED_CIDR to your IP, e.g. ALLOWED_CIDR=203.0.113.7/32}"

cdk bootstrap "aws://$CDK_DEFAULT_ACCOUNT/$AWS_REGION" -c allowedCidr="$ALLOWED_CIDR"
cdk deploy --all --require-approval never -c allowedCidr="$ALLOWED_CIDR" \
  ${ALARM_EMAIL:+-c alarmEmail="$ALARM_EMAIL"}
echo "Deployed. Run scripts/aws-destroy.sh when you are done: it is billed while it runs."
