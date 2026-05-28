FROM node:20-alpine

WORKDIR /code

# Copy packages configuration files
COPY package*.json /code/

# Install dependencies
RUN npm install

# Copy all project files
COPY . /code/

# Expose Vite default dev port
EXPOSE 5173

# Run Vite in host-bound mode so it can be reached from the MacBook host
CMD ["npm", "run", "dev", "--", "--host"]
