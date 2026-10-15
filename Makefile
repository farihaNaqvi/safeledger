.PHONY: up down test build logs ps clean

# Local dev loop. Equivalent to the scripts under scripts/ for environments
# without `make` (e.g. plain Windows without Git Bash / WSL make installed).

up:
	docker compose -f infra/docker-compose.yml up --build -d

down:
	docker compose -f infra/docker-compose.yml down

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
