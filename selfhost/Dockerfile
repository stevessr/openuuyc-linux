FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN mkdir -p /app/selfhost
COPY __init__.py server.py server_utils.py compat.py ice.py media.py /app/selfhost/
RUN useradd -r -u 10001 openuuyc && mkdir -p /data && chown openuuyc /data
USER openuuyc
EXPOSE 8844
ENTRYPOINT ["python", "-m", "selfhost.server"]
CMD ["--bind", "0.0.0.0", "--port", "8844", "--database", "/data/selfhost.sqlite3"]
