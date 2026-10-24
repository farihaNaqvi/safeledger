.PHONY: up up-observability down test build logs ps clean aws-deploy destroy

# Local dev loop. Equivalent to the scripts under scripts/ for environments
# without `make` (e.g. plain Windows without Git Bash / WSL make installed).

up:
	docker compose -f infra/docker-compose.yml up --build -d

up-observability:
	docker compose -f infra/docker-compose.yml -f infra/docker-compose.observability.yml up --build -d

down:
	docker compose -f infra/docker-compose.yml -f infra/docker-compose.observability.yml down

test:
	mvn verify

build:
	mvn -DskipTests package

logs:
	docker compose -f infra/docker-compose.yml logs -f

ps:
	docker compose -f infra/docker-compose.yml ps

clean:
	docker compose -f infra/docker-compose.yml down -v
	mvn clean

# AWS (billed while deployed). ALLOWED_CIDR=203.0.113.7/32 make aws-deploy
aws-deploy:
	scripts/aws-deploy.sh

destroy:
	scripts/aws-destroy.sh
