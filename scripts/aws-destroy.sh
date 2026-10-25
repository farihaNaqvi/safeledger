#!/usr/bin/env bash
# Removes every SafeLedger stack, then lists anything still tagged Project=SafeLedger.
. "$(dirname "${BASH_SOURCE[0]}")/aws-common.sh"

# The CIDR only matters for synth; nothing is created on destroy.
cdk destroy --all --force -c allowedCidr=127.0.0.1/32

echo "Stacks left:"
"$AWS" cloudformation list-stacks --query \
  "StackSummaries[?starts_with(StackName,'SafeLedger') && StackStatus!='DELETE_COMPLETE'].[StackName,StackStatus]" \
  --output text | tr -d '\r'
echo "Resources still tagged Project=SafeLedger (secrets pending deletion may show for a while):"
"$AWS" resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=SafeLedger \
  --query "ResourceTagMappingList[].ResourceARN" --output text | tr -d '\r' | tr '\t' '\n'
