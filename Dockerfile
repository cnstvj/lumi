FROM node:20-alpine
WORKDIR /app
COPY coordinator/package.json .
RUN npm install --production
COPY coordinator/server.js .
EXPOSE 4000
CMD ["node", "server.js"]
